/**
 * Lumina AI Orchestrator - TypeScript Reference Implementation
 * Runs on Node.js with Express + ioredis. Mirrors Python semantics
 * so teams can adopt either runtime without redesign.
 */
import express, { Request, Response, NextFunction } from "express";
import Redis from "ioredis";
import crypto from "crypto";
import dotenv from "dotenv";
import { z } from "zod";

dotenv.config();

const PORT = parseInt(process.env.PORT || "3000", 10);
const REDIS_HOST = process.env.REDIS_HOST || "localhost";
const REDIS_PORT = parseInt(process.env.REDIS_PORT || "6379", 10);
const API_TOKEN = process.env.LUMINA_API_TOKEN || "dev-token";

const app = express();
app.use(express.json());

enum Intent {
  HR_POLICY = "hr_policy",
  IT_SUPPORT = "it_support",
  KNOWLEDGE_RETRIEVAL = "knowledge_retrieval",
  PTO_BALANCE = "pto_balance",
  PASSWORD_RESET = "password_reset",
  ESCALATE = "escalate",
}

enum ConfidenceTier {
  HIGH = "high",
  MEDIUM = "medium",
  LOW = "low",
}

type Policy = {
  roles: string[];
  dataClassification: string;
  requireApproval: boolean;
};

type ToolParams = Record<string, unknown>;

type ToolHandler = (params: ToolParams, policy: Policy) => Promise<unknown>;

type Step = {
  toolId: string;
  params: ToolParams;
  dependsOn?: string;
};

type Plan = {
  steps: Step[];
};

const UserRequestSchema = z.object({
  text: z.string().min(1),
  sessionId: z.string().optional(),
  userId: z.string(),
  tenantId: z.string(),
  channel: z.string().default("web"),
});

const redis = new Redis({ host: REDIS_HOST, port: REDIS_PORT });

class ContextWindow {
  private ttl = 3600;

  private key(sessionId: string, tenantId: string): string {
    const safe = crypto.createHash("sha256").update(`${tenantId}:${sessionId}`).digest("hex");
    return `lumina:ctx:${safe}`;
  }

  async load(sessionId: string, userId: string, tenantId: string, limit = 12): Promise<Array<Record<string, unknown>>> {
    const key = this.key(sessionId, tenantId);
    const raw = await redis.lrange(key, -limit, -1);
    const history = raw.map((item) => JSON.parse(item));
    history.push({ role: "system", userId, tenantId, ts: Date.now() / 1000 });
    return history;
  }

  async append(sessionId: string, tenantId: string, role: string, content: Record<string, unknown>): Promise<void> {
    const key = this.key(sessionId, tenantId);
    const payload = JSON.stringify({ role, content, ts: Date.now() / 1000 });
    await redis.rpush(key, payload);
    await redis.expire(key, this.ttl);
  }

  async clear(sessionId: string, tenantId: string): Promise<void> {
    await redis.del(this.key(sessionId, tenantId));
  }
}

class IntentRouter {
  private keywords: Record<Intent, string[]> = {
    [Intent.PASSWORD_RESET]: ["password", "reset", "vpn", "token", "login", "locked"],
    [Intent.PTO_BALANCE]: ["pto", "vacation", "time off", "balance", "days off"],
    [Intent.HR_POLICY]: ["policy", "handbook", "benefits", "hr", "parental leave"],
    [Intent.IT_SUPPORT]: ["ticket", "software", "install", "laptop", "issue", "broken"],
    [Intent.KNOWLEDGE_RETRIEVAL]: ["doc", "document", "how to", "faq", "find", "wiki"],
    [Intent.ESCALATE]: [],
  };

  async classify(text: string): Promise<{ intent: Intent; confidence: ConfidenceTier; score: number }> {
    const lower = text.toLowerCase();
    const scores = new Map<Intent, number>();
    for (const intent of Object.values(Intent)) {
      const kws = this.keywords[intent] || [];
      scores.set(intent, kws.filter((kw) => lower.includes(kw)).length);
    }
    let best: Intent = Intent.ESCALATE;
    let maxScore = -1;
    scores.forEach((score, intent) => {
      if (score > maxScore) {
        maxScore = score;
        best = intent;
      }
    });
    let confidence: ConfidenceTier;
    if (maxScore >= 3) confidence = ConfidenceTier.HIGH;
    else if (maxScore >= 1) confidence = ConfidenceTier.MEDIUM;
    else confidence = ConfidenceTier.LOW;
    if (confidence === ConfidenceTier.LOW) best = Intent.ESCALATE;
    return { intent: best, confidence, score: Math.min(maxScore / 5, 0.99) };
  }
}

class ToolRegistry {
  private tools = new Map<string, ToolHandler>();

  register(toolId: string, handler: ToolHandler): void {
    this.tools.set(toolId, handler);
  }

  resolve(toolId: string): ToolHandler {
    const handler = this.tools.get(toolId);
    if (!handler) throw new Error(`Tool ${toolId} not found`);
    return handler;
  }

  async execute(toolId: string, params: ToolParams, policy: Policy): Promise<unknown> {
    return this.resolve(toolId)(params, policy);
  }
}

class Planner {
  generate(intent: Intent, context: Array<Record<string, unknown>>): Plan {
    const userMsg = [...context].reverse().find((m) => m.role === "user") || {};
    const text = ((userMsg.content as Record<string, unknown>)?.text as string) || "";
    const userId = (context[context.length - 1]?.content as Record<string, unknown>)?.userId as string;
    const steps: Step[] = [];
    switch (intent) {
      case Intent.PASSWORD_RESET:
        steps.push({ toolId: "verify_identity", params: { factor: "sso" } });
        steps.push({ toolId: "reset_token", params: { service: "vpn" } });
        break;
      case Intent.PTO_BALANCE:
        steps.push({ toolId: "fetch_pto", params: { userId } });
        break;
      case Intent.IT_SUPPORT:
        steps.push({ toolId: "create_ticket", params: { subject: text.slice(0, 120), queue: "l2" } });
        break;
      case Intent.HR_POLICY:
        steps.push({ toolId: "search_kb", params: { query: text } });
        break;
      default:
        steps.push({ toolId: "escalate", params: { reason: "low_confidence" } });
    }
    return { steps };
  }
}

class AuditLogger {
  async log(tenantId: string, userId: string, intent: Intent, steps: Step[], results: unknown[]): Promise<string> {
    const traceId = crypto.createHash("sha256").update(`${tenantId}:${userId}:${Date.now()}`).digest("hex").slice(0, 16);
    console.log(`audit trace=${traceId} tenant=${tenantId} user=${userId} intent=${intent} steps=${steps.map((s) => s.toolId).join(",")}`);
    return traceId;
  }
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

const registry = new ToolRegistry();
const intentRouter = new IntentRouter();
const planner = new Planner();
const audit = new AuditLogger();
const contextWindow = new ContextWindow();

registry.register("verify_identity", async (params) => {
  await sleep(50);
  return { verified: true, factor: params.factor || "sso" };
});

registry.register("reset_token", async (params) => {
  await sleep(80);
  const token = crypto.randomBytes(24).toString("hex");
  return { token, expiresIn: 900, service: params.service || "vpn" };
});

registry.register("fetch_pto", async (params) => {
  await sleep(60);
  return { remainingDays: 14, pendingRequests: 1, userId: params.userId };
});

registry.register("create_ticket", async (params) => {
  await sleep(100);
  const ticketId = `TKT-${Date.now() % 100000}`;
  return { ticketId, queue: params.queue || "l1", subject: params.subject || "" };
});

registry.register("search_kb", async (params) => {
  await sleep(70);
  return { results: [{ title: "Remote Work Policy", url: "/kb/remote-work" }], query: params.query || "" };
});

registry.register("escalate", async (params) => {
  return { escalated: true, reason: params.reason || "manual", team: "human_support" };
});

class Orchestrator {
  async handle(req: z.infer<typeof UserRequestSchema>, policy: Policy): Promise<Record<string, unknown>> {
    const sessionId = req.sessionId || crypto.createHash("sha256").update(`${req.userId}:${Date.now()}`).digest("hex").slice(0, 16);
    await contextWindow.append(sessionId, req.tenantId, "user", {
      text: req.text,
      userId: req.userId,
      channel: req.channel,
    });

    let { intent, confidence, score } = await intentRouter.classify(req.text);
    if (confidence === ConfidenceTier.LOW) intent = Intent.ESCALATE;

    const history = await contextWindow.load(sessionId, req.userId, req.tenantId);
    const plan = planner.generate(intent, history);

    const results: unknown[] = [];
    for (const step of plan.steps) {
      if (policy.requireApproval && ["reset_token", "create_ticket"].includes(step.toolId)) {
        results.push({ tool: step.toolId, status: "pending_approval" });
        continue;
      }
      results.push(await registry.execute(step.toolId, step.params, policy));
    }

    await contextWindow.append(sessionId, req.tenantId, "assistant", { intent: intent.valueOf(), results });
    const traceId = await audit.log(req.tenantId, req.userId, intent, plan.steps, results);

    return {
      sessionId,
      intent: intent.valueOf(),
      confidence: confidence.valueOf(),
      score: Math.round(score * 1000) / 1000,
      steps: plan.steps.map((s) => ({ tool: s.toolId, params: s.params })),
      results,
      traceId,
    };
  }
}

const orchestrator = new Orchestrator();

function auth(req: Request, res: Response, next: NextFunction): void {
  const token = req.headers["x-lumina-token"];
  if (token !== API_TOKEN) {
    res.status(401).json({ error: "Invalid token" });
    return;
  }
  next();
}

app.get("/health", (_req, res) => {
  res.json({ status: "ok", version: "3.0.0" });
});

app.get("/api/v1/intents", (_req, res) => {
  res.json(Object.values(Intent));
});

app.post("/api/v1/chat", auth, async (req, res) => {
  try {
    const parsed = UserRequestSchema.parse(req.body);
    const policy: Policy = { roles: ["authenticated"], dataClassification: "internal", requireApproval: false };
    const result = await orchestrator.handle(parsed, policy);
    res.json(result);
  } catch (err) {
    console.error("chat_error", err);
    res.status(500).json({ error: (err as Error).message });
  }
});

app.post("/api/v1/chat/stream", auth, async (req, res) => {
  const parsed = UserRequestSchema.parse(req.body);
  const policy: Policy = { roles: ["authenticated"], dataClassification: "internal", requireApproval: false };
  const result = await orchestrator.handle(parsed, policy);

  res.setHeader("Content-Type", "text/event-stream");
  res.setHeader("Cache-Control", "no-cache");
  res.setHeader("Connection", "keep-alive");

  const chunks = [
    { type: "intent", value: result.intent },
    { type: "thought", value: `Executing ${(result.steps as Step[]).length} tool steps` },
  ];
  for (const [step, r] of (result.steps as Step[]).entries()) {
    chunks.push({ type: "tool", tool: step.toolId, result: r });
  }
  chunks.push({ type: "done", traceId: result.traceId });

  let i = 0;
  const interval = setInterval(() => {
    if (i >= chunks.length) {
      clearInterval(interval);
      res.end();
      return;
    }
    res.write(`data: ${JSON.stringify(chunks[i])}\n\n`);
    i++;
  }, 100);
});

app.post("/api/v1/sessions/:sessionId/clear", async (req, res) => {
  const tenantId = req.headers["x-tenant-id"];
  if (typeof tenantId !== "string") {
    res.status(400).json({ error: "Missing tenant id" });
    return;
  }
  await contextWindow.clear(req.params.sessionId, tenantId);
  res.json({ cleared: true });
});

app.listen(PORT, () => {
  console.log(`Lumina TS orchestrator listening on port ${PORT}`);
});
