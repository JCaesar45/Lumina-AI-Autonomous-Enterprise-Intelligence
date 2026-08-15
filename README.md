# Lumina AI — Autonomous Enterprise Intelligence

> **One conversational layer. Multi-model reasoning. Secure tool execution. Complete observability.**

Lumina is a reference architecture for an internal AI assistant like the HelpBot systems many enterprises run inside Slack: HR onboarding, IT support, knowledge retrieval, PTO lookups, password resets, and human escalation. Unlike a simple chatbot, Lumina is built as a **polyglot, production-grade orchestration platform** with isolated intent routing, persistent session memory, a secure tool registry, and audited execution across Python, TypeScript, and Java.

This repository is intentionally opinionated. It demonstrates how to take an internal assistant from "useful demo" to "trustworthy production service": clear separation of concerns, fail-closed policy checks, idempotent operations, and readable code that engineering teams can actually maintain.

---

## Table of Contents

1. [What This Builds](#what-this-builds)
2. [Product Structure](#product-structure)
3. [Architecture](#architecture)
4. [Repository Layout](#repository-layout)
5. [Quick Start](#quick-start)
   - [Python](#python)
   - [TypeScript](#typescript)
   - [Java](#java)
6. [API Reference](#api-reference)
7. [Frontend](#frontend)
8. [Design Decisions](#design-decisions)
9. [Production Checklist](#production-checklist)
10. [References](#references)

---

## What This Builds

Lumina accepts a natural-language request, determines what the user wants, loads the relevant conversation and user context, generates a plan of tool calls, executes those calls through a governed registry, and returns a coherent response. Every action is traceable.

Supported intents out of the box:

| Intent | Example Trigger | Tools Executed |
|---|---|---|
| `password_reset` | "reset my VPN token" | `verify_identity`, `reset_token` |
| `pto_balance` | "how much PTO do I have" | `fetch_pto` |
| `it_support` | "my laptop is broken" | `create_ticket` |
| `hr_policy` | "what is parental leave" | `search_kb` |
| `knowledge_retrieval` | "find the remote work doc" | `search_kb` |
| `escalate` | ambiguous or sensitive request | `escalate` |

---

## Product Structure

```
Lumina AI Platform
├── lumina.ai (marketing site + interactive demo)
├── Orchestrator Service (choose one runtime)
│   ├── Python / FastAPI
│   ├── TypeScript / Node.js / Express
│   └── Java / Javalin
├── Context Store
│   └── Redis (session windows, TTL, tenant isolation)
├── Tool Registry
│   ├── IT tools (password reset, ticket creation)
│   ├── HR tools (PTO, policy search)
│   └── Escalation / human handoff
├── Policy & Guardrails
│   ├── Role-based approval gates
│   ├── PII-aware parameters
│   └── Audit logging
└── Observability
    ├── Structured logs per trace
    └── SSE streaming for real-time UX
```

---

## Architecture

The request lifecycle is deliberately linear and observable:

1. **Ingest** — validate auth, tenant, and user identity.
2. **Classify** — route the message to the correct intent.
3. **Assemble** — build a context window from Redis history + current request.
4. **Plan** — generate a deterministic sequence of tool calls.
5. **Execute** — invoke tools through the registry with policy checks.
6. **Log** — write an audit trace.
7. **Respond** — return JSON or stream Server-Sent Events.

The same pipeline is implemented in all three backends so teams can adopt Lumina without rewriting the logic for their preferred stack.

---

## Repository Layout

```
.
├── index.html              # Single-file luxury marketing site + demo chat
├── README.md               # This file
├── docs/
│   └── ALGORITHM.md        # Detailed algorithmic design notes
└── backend/
    ├── python/
    │   ├── orchestrator.py
    │   ├── requirements.txt
    │   ├── Dockerfile
    │   └── docker-compose.yml
    ├── typescript/
    │   ├── orchestrator.ts
    │   ├── package.json
    │   ├── tsconfig.json
    │   └── Dockerfile
    └── java/
        ├── pom.xml
        ├── Dockerfile
        └── src/main/java/com/lumina/ai/
            └── OrchestratorApplication.java
```

---

## Quick Start

### Python

```bash
cd backend/python
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
redis-server &
LUMINA_API_TOKEN=dev-token python orchestrator.py
```

Test it:

```bash
curl -X POST http://localhost:8000/api/v1/chat \
  -H "Content-Type: application/json" \
  -H "x-lumina-token: dev-token" \
  -d '{"text":"reset my vpn token","userId":"u123","tenantId":"acme"}'
```

### TypeScript

```bash
cd backend/typescript
npm install
npm run build
REDIS_HOST=localhost LUMINA_API_TOKEN=dev-token npm start
```

Test it:

```bash
curl -X POST http://localhost:3000/api/v1/chat \
  -H "Content-Type: application/json" \
  -H "x-lumina-token: dev-token" \
  -d '{"text":"how much pto do i have","userId":"u123","tenantId":"acme"}'
```

### Java

```bash
cd backend/java
mvn clean package -DskipTests
REDIS_HOST=localhost LUMINA_API_TOKEN=dev-token java -jar target/lumina-orchestrator-3.0.0.jar
```

Test it:

```bash
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -H "x-lumina-token: dev-token" \
  -d '{"text":"open an IT ticket for my laptop","userId":"u123","tenantId":"acme"}'
```

---

## API Reference

### `POST /api/v1/chat`

Run a single request through the full pipeline.

**Headers:**
- `Content-Type: application/json`
- `x-lumina-token: <token>`

**Body:**
```json
{
  "text": "reset my vpn token",
  "sessionId": "optional-existing-id",
  "userId": "u123",
  "tenantId": "acme",
  "channel": "web"
}
```

**Response:**
```json
{
  "sessionId": "abc...",
  "intent": "password_reset",
  "confidence": "high",
  "score": 0.6,
  "steps": [...],
  "results": [...],
  "traceId": "a1b2c3d4"
}
```

### `POST /api/v1/chat/stream`

Same logic as `/chat`, delivered as Server-Sent Events for real-time UI updates.

### `POST /api/v1/sessions/{sessionId}/clear`

Clear a session context. Requires `x-tenant-id` header.

### `GET /api/v1/intents`

List supported intents.

### `GET /health`

Health check.

---

## Frontend

Open `index.html` in any modern browser. It is a self-contained, single-file luxury landing page that includes:

- Responsive dark UI with animated particle constellation background
- Interactive 3D-tilt demo chat card
- Live typing code showcase
- Animated counters, scroll reveals, and pricing cards
- Mobile navigation and accessible markup

No build step required.

---

## Design Decisions

- **Polyglot parity.** The same orchestration logic is implemented in Python, TypeScript, and Java. This lets infrastructure, platform, and application teams each use the runtime that fits them.
- **Redis-backed sessions.** Context windows are isolated by SHA-256 hashed tenant/session keys, have TTL-based expiry, and support sliding-window recall.
- **Policy gates inside execution.** Tool calls can be short-circuited into "pending approval" based on the caller's policy context, not just ACL metadata.
- **Deterministic planner.** The reference planner is rule-based to guarantee predictable behavior. Replace it with an LLM planner when branching logic becomes complex.
- **Streaming by default.** Both JSON and SSE endpoints exist because conversational UIs feel broken without incremental feedback.
- **Security over convenience.** Tokens are required on every stateful endpoint. Tool parameters can be marked as PII. Session keys are hashed.

---

## Production Checklist

- [ ] Replace keyword classifier with an embedding or fine-tuned model.
- [ ] Add OAuth2/OIDC user authentication and tenant isolation in middleware.
- [ ] Implement real tool integrations (Workday, ServiceNow, Okta, etc.) behind an internal API gateway.
- [ ] Store audit traces in a durable system (PostgreSQL, ClickHouse, S3) rather than logs alone.
- [ ] Add OpenTelemetry tracing and structured metrics.
- [ ] Run load tests and set autoscaling policies.
- [ ] Add PII redaction and output moderation.
- [ ] Define SLOs and on-call runbooks for integration failures.

---

## References

Géron, A. (2022). *Hands-on machine learning with Scikit-Learn, Keras, and TensorFlow* (3rd ed.). O'Reilly Media.

Hohpe, G., & Woolf, B. (2003). *Enterprise integration patterns: Designing, building, and deploying messaging solutions*. Addison-Wesley Professional.

Lewis, P., Perez, E., Piktus, A., Petroni, F., Karpukhin, V., Goyal, N., Küttler, H., Lewis, M., Yih, W.-t., Rocktäschel, T., Riedel, S., & Kiela, D. (2020). Retrieval-augmented generation for knowledge-intensive NLP tasks. *Advances in Neural Information Processing Systems*, 33, 9459–9474. https://doi.org/10.48550/arXiv.2005.11401

Martin, R. C. (2008). *Clean code: A handbook of agile software craftsmanship*. Prentice Hall.

Newman, S. (2021). *Building microservices* (2nd ed.). O'Reilly Media.

OpenAI. (2024). *Function calling*. OpenAI Platform documentation. https://platform.openai.com/docs/guides/function-calling

Vaswani, A., Shazeer, N., Parmar, N., Uszkoreit, J., Jones, L., Gomez, A. N., Kaiser, Ł., & Polosukhin, I. (2017). Attention is all you need. *Advances in Neural Information Processing Systems*, 30. https://doi.org/10.48550/arXiv.1706.03762
