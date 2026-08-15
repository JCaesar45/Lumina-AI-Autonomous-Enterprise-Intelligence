package com.lumina.ai;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class OrchestratorApplication {

    private static final String API_TOKEN = System.getenv().getOrDefault("LUMINA_API_TOKEN", "dev-token");
    private static final String REDIS_HOST = System.getenv().getOrDefault("REDIS_HOST", "localhost");
    private static final int REDIS_PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
    private static final int TTL_SECONDS = 3600;

    private final JedisPool jedisPool;
    private final IntentRouter intentRouter = new IntentRouter();
    private final Planner planner = new Planner();
    private final ToolRegistry registry = new ToolRegistry();
    private final AuditLogger audit = new AuditLogger();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public OrchestratorApplication() {
        JedisPoolConfig config = new JedisPoolConfig();
        this.jedisPool = new JedisPool(config, REDIS_HOST, REDIS_PORT);
        registerTools();
    }

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        OrchestratorApplication app = new OrchestratorApplication();
        Javalin server = Javalin.create(cfg -> cfg.showJavalinBanner = false).start(port);

        server.get("/health", ctx -> ctx.json(Map.of("status", "ok", "version", "3.0.0")));
        server.get("/api/v1/intents", ctx -> ctx.json(Arrays.stream(Intent.values()).map(Enum::name).collect(Collectors.toList())));
        server.post("/api/v1/chat", app::auth, app::chat);
        server.post("/api/v1/chat/stream", app::auth, app::chatStream);
        server.post("/api/v1/sessions/{sessionId}/clear", app::clearSession);

        Runtime.getRuntime().addShutdownHook(new Thread(app::shutdown));
    }

    private void auth(Context ctx) {
        String token = ctx.header("x-lumina-token");
        if (!API_TOKEN.equals(token)) {
            ctx.status(HttpStatus.UNAUTHORIZED).json(Map.of("error", "Invalid token"));
            throw new RuntimeException("unauthorized");
        }
    }

    private void chat(Context ctx) {
        try {
            UserRequest req = mapper.readValue(ctx.body(), UserRequest.class);
            Policy policy = new Policy(List.of("authenticated"), "internal", false);
            Map<String, Object> result = handle(req, policy);
            ctx.json(result);
        } catch (Exception e) {
            e.printStackTrace();
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", e.getMessage()));
        }
    }

    private void chatStream(Context ctx) {
        try {
            UserRequest req = mapper.readValue(ctx.body(), UserRequest.class);
            Policy policy = new Policy(List.of("authenticated"), "internal", false);
            Map<String, Object> result = handle(req, policy);

            ctx.contentType("text/event-stream");
            ctx.header("Cache-Control", "no-cache");
            ctx.header("Connection", "keep-alive");

            List<Map<String, Object>> chunks = new ArrayList<>();
            chunks.add(Map.of("type", "intent", "value", result.get("intent")));
            List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
            chunks.add(Map.of("type", "thought", "value", "Executing " + steps.size() + " tool steps"));
            List<Object> results = (List<Object>) result.get("results");
            for (int i = 0; i < steps.size(); i++) {
                Map<String, Object> chunk = new LinkedHashMap<>(steps.get(i));
                chunk.put("type", "tool");
                chunk.put("result", results.get(i));
                chunks.add(chunk);
            }
            chunks.add(Map.of("type", "done", "traceId", result.get("traceId")));

            ctx.res().setCharacterEncoding("UTF-8");
            var out = ctx.res().getOutputStream();
            final int[] idx = {0};
            Runnable emitter = new Runnable() {
                @Override
                public void run() {
                    if (idx[0] >= chunks.size()) {
                        ctx.res().setComplete();
                        return;
                    }
                    try {
                        out.write(("data: " + mapper.writeValueAsString(chunks.get(idx[0])) + "\n\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        idx[0]++;
                        scheduler.schedule(this, 100, TimeUnit.MILLISECONDS);
                    } catch (Exception ignored) {
                        ctx.res().setComplete();
                    }
                }
            };
            scheduler.schedule(emitter, 0, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            e.printStackTrace();
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", e.getMessage()));
        }
    }

    private void clearSession(Context ctx) {
        String tenantId = ctx.header("x-tenant-id");
        if (tenantId == null) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Missing tenant id"));
            return;
        }
        String sessionId = ctx.pathParam("sessionId");
        clearContext(sessionId, tenantId);
        ctx.json(Map.of("cleared", true));
    }

    public Map<String, Object> handle(UserRequest req, Policy policy) throws Exception {
        String sessionId = req.sessionId != null ? req.sessionId : sha256(req.userId + System.currentTimeMillis()).substring(0, 16);
        appendContext(sessionId, req.tenantId, "user", Map.of("text", req.text, "userId", req.userId, "channel", req.channel));

        Classification classification = intentRouter.classify(req.text);
        if (classification.confidence == ConfidenceTier.LOW) {
            classification = new Classification(Intent.ESCALATE, ConfidenceTier.LOW, classification.score);
        }

        List<Map<String, Object>> history = loadContext(sessionId, req.userId, req.tenantId);
        Plan plan = planner.generate(classification.intent, history);

        List<Object> results = new ArrayList<>();
        for (Step step : plan.steps) {
            if (policy.requireApproval && ("reset_token".equals(step.toolId) || "create_ticket".equals(step.toolId))) {
                results.add(Map.of("tool", step.toolId, "status", "pending_approval"));
                continue;
            }
            results.add(registry.execute(step.toolId, step.params, policy));
        }

        appendContext(sessionId, req.tenantId, "assistant", Map.of("intent", classification.intent.name(), "results", results));
        String traceId = audit.log(req.tenantId, req.userId, classification.intent, plan.steps, results);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", sessionId);
        response.put("intent", classification.intent.name());
        response.put("confidence", classification.confidence.name().toLowerCase());
        response.put("score", Math.round(classification.score * 1000.0) / 1000.0);
        response.put("steps", plan.steps.stream().map(s -> Map.of("tool", s.toolId, "params", s.params)).collect(Collectors.toList()));
        response.put("results", results);
        response.put("traceId", traceId);
        return response;
    }

    private List<Map<String, Object>> loadContext(String sessionId, String userId, String tenantId) throws Exception {
        String key = contextKey(sessionId, tenantId);
        List<Map<String, Object>> history = new ArrayList<>();
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> raw = jedis.lrange(key, -12, -1);
            for (String item : raw) {
                history.add(mapper.readValue(item, Map.class));
            }
        }
        history.add(Map.of("role", "system", "userId", userId, "tenantId", tenantId, "ts", Instant.now().getEpochSecond()));
        return history;
    }

    private void appendContext(String sessionId, String tenantId, String role, Map<String, Object> content) throws Exception {
        String key = contextKey(sessionId, tenantId);
        String payload = mapper.writeValueAsString(Map.of("role", role, "content", content, "ts", Instant.now().getEpochSecond()));
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.rpush(key, payload);
            jedis.expire(key, TTL_SECONDS);
        }
    }

    private void clearContext(String sessionId, String tenantId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(contextKey(sessionId, tenantId));
        }
    }

    private String contextKey(String sessionId, String tenantId) {
        return "lumina:ctx:" + sha256(tenantId + ":" + sessionId);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private void registerTools() {
        registry.register("verify_identity", (params, policy) -> {
            Thread.sleep(50);
            return Map.of("verified", true, "factor", params.getOrDefault("factor", "sso"));
        });
        registry.register("reset_token", (params, policy) -> {
            Thread.sleep(80);
            String token = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            return Map.of("token", token, "expiresIn", 900, "service", params.getOrDefault("service", "vpn"));
        });
        registry.register("fetch_pto", (params, policy) -> {
            Thread.sleep(60);
            return Map.of("remainingDays", 14, "pendingRequests", 1, "userId", params.get("userId"));
        });
        registry.register("create_ticket", (params, policy) -> {
            Thread.sleep(100);
            String ticketId = "TKT-" + (System.currentTimeMillis() % 100000);
            return Map.of("ticketId", ticketId, "queue", params.getOrDefault("queue", "l1"), "subject", params.getOrDefault("subject", ""));
        });
        registry.register("search_kb", (params, policy) -> {
            Thread.sleep(70);
            return Map.of("results", List.of(Map.of("title", "Remote Work Policy", "url", "/kb/remote-work")), "query", params.getOrDefault("query", ""));
        });
        registry.register("escalate", (params, policy) -> Map.of("escalated", true, "reason", params.getOrDefault("reason", "manual"), "team", "human_support"));
    }

    private void shutdown() {
        jedisPool.close();
        scheduler.shutdown();
    }

    // Domain models
    static class UserRequest {
        public String text;
        public String sessionId;
        public String userId;
        public String tenantId;
        public String channel = "web";
    }

    static class Policy {
        final List<String> roles;
        final String dataClassification;
        final boolean requireApproval;

        Policy(List<String> roles, String dataClassification, boolean requireApproval) {
            this.roles = roles;
            this.dataClassification = dataClassification;
            this.requireApproval = requireApproval;
        }
    }

    enum Intent {
        HR_POLICY, IT_SUPPORT, KNOWLEDGE_RETRIEVAL, PTO_BALANCE, PASSWORD_RESET, ESCALATE
    }

    enum ConfidenceTier { HIGH, MEDIUM, LOW }

    record Classification(Intent intent, ConfidenceTier confidence, double score) {}

    static class Step {
        final String toolId;
        final Map<String, Object> params;
        final String dependsOn;

        Step(String toolId, Map<String, Object> params, String dependsOn) {
            this.toolId = toolId;
            this.params = params;
            this.dependsOn = dependsOn;
        }
    }

    static class Plan {
        final List<Step> steps;
        Plan(List<Step> steps) { this.steps = steps; }
    }

    @FunctionalInterface
    interface ToolHandler {
        Object execute(Map<String, Object> params, Policy policy) throws Exception;
    }

    static class ToolRegistry {
        private final Map<String, ToolHandler> tools = new HashMap<>();

        void register(String toolId, ToolHandler handler) { tools.put(toolId, handler); }

        ToolHandler resolve(String toolId) {
            ToolHandler handler = tools.get(toolId);
            if (handler == null) throw new RuntimeException("Tool not found: " + toolId);
            return handler;
        }

        Object execute(String toolId, Map<String, Object> params, Policy policy) throws Exception {
            return resolve(toolId).execute(params, policy);
        }
    }

    static class IntentRouter {
        private final Map<Intent, List<String>> keywords = Map.of(
            Intent.PASSWORD_RESET, List.of("password", "reset", "vpn", "token", "login", "locked"),
            Intent.PTO_BALANCE, List.of("pto", "vacation", "time off", "balance", "days off"),
            Intent.HR_POLICY, List.of("policy", "handbook", "benefits", "hr", "parental leave"),
            Intent.IT_SUPPORT, List.of("ticket", "software", "install", "laptop", "issue", "broken"),
            Intent.KNOWLEDGE_RETRIEVAL, List.of("doc", "document", "how to", "faq", "find", "wiki"),
            Intent.ESCALATE, List.of()
        );

        Classification classify(String text) {
            String lower = text.toLowerCase();
            Map<Intent, Integer> scores = new EnumMap<>(Intent.class);
            for (Intent intent : Intent.values()) scores.put(intent, 0);
            for (Map.Entry<Intent, List<String>> entry : keywords.entrySet()) {
                int score = 0;
                for (String kw : entry.getValue()) if (lower.contains(kw)) score++;
                scores.put(entry.getKey(), score);
            }
            Intent best = Intent.ESCALATE;
            int maxScore = -1;
            for (Map.Entry<Intent, Integer> e : scores.entrySet()) {
                if (e.getValue() > maxScore) { maxScore = e.getValue(); best = e.getKey(); }
            }
            ConfidenceTier confidence;
            if (maxScore >= 3) confidence = ConfidenceTier.HIGH;
            else if (maxScore >= 1) confidence = ConfidenceTier.MEDIUM;
            else confidence = ConfidenceTier.LOW;
            if (confidence == ConfidenceTier.LOW) best = Intent.ESCALATE;
            return new Classification(best, confidence, Math.min(maxScore / 5.0, 0.99));
        }
    }

    static class Planner {
        Plan generate(Intent intent, List<Map<String, Object>> context) {
            String text = "";
            for (int i = context.size() - 1; i >= 0; i--) {
                Map<String, Object> m = context.get(i);
                if ("user".equals(m.get("role"))) {
                    Map<String, Object> c = (Map<String, Object>) m.get("content");
                    text = (String) c.getOrDefault("text", "");
                    break;
                }
            }
            String userId = "";
            Map<String, Object> last = context.get(context.size() - 1);
            Map<String, Object> lastContent = (Map<String, Object>) last.get("content");
            if (lastContent != null) userId = (String) lastContent.get("userId");

            List<Step> steps = new ArrayList<>();
            switch (intent) {
                case PASSWORD_RESET -> {
                    steps.add(new Step("verify_identity", Map.of("factor", "sso"), null));
                    steps.add(new Step("reset_token", Map.of("service", "vpn"), null));
                }
                case PTO_BALANCE -> steps.add(new Step("fetch_pto", Map.of("userId", userId), null));
                case IT_SUPPORT -> steps.add(new Step("create_ticket", Map.of("subject", text.substring(0, Math.min(text.length(), 120)), "queue", "l2"), null));
                case HR_POLICY -> steps.add(new Step("search_kb", Map.of("query", text), null));
                default -> steps.add(new Step("escalate", Map.of("reason", "low_confidence"), null));
            }
            return new Plan(steps);
        }
    }

    static class AuditLogger {
        String log(String tenantId, String userId, Intent intent, List<Step> steps, List<Object> results) throws Exception {
            String traceId = sha256(tenantId + userId + System.currentTimeMillis()).substring(0, 16);
            String stepNames = steps.stream().map(s -> s.toolId).collect(Collectors.joining(","));
            System.out.println("audit trace=" + traceId + " tenant=" + tenantId + " user=" + userId + " intent=" + intent + " steps=" + stepNames);
            return traceId;
        }
    }
}
