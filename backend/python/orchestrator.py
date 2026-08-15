"""
Lumina AI Orchestrator - Python Reference Implementation
Microservice-ready FastAPI backend for intent routing, context assembly,
and secure tool execution.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, AsyncIterator, Callable, Coroutine, Dict, List, Optional

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from redis.asyncio import Redis

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("lumina")

app = FastAPI(title="Lumina AI Orchestrator", version="3.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


class Intent(str, Enum):
    HR_POLICY = "hr_policy"
    IT_SUPPORT = "it_support"
    KNOWLEDGE_RETRIEVAL = "knowledge_retrieval"
    PTO_BALANCE = "pto_balance"
    PASSWORD_RESET = "password_reset"
    ESCALATE = "escalate"


class ConfidenceTier(str, Enum):
    HIGH = "high"
    MEDIUM = "medium"
    LOW = "low"


class ToolParam(BaseModel):
    key: str
    value: Any
    pii: bool = False


class ToolRequest(BaseModel):
    tool_id: str
    params: List[ToolParam] = Field(default_factory=list)


class UserRequest(BaseModel):
    text: str
    session_id: Optional[str] = None
    user_id: str
    tenant_id: str
    channel: str = "web"


class PolicyContext(BaseModel):
    roles: List[str] = Field(default_factory=list)
    data_classification: str = "internal"
    require_approval: bool = False


class ResponseSchema(BaseModel):
    format: str = "markdown"
    tone: str = "professional"


@dataclass
class Step:
    tool_id: str
    params: Dict[str, Any]
    depends_on: Optional[str] = None


@dataclass
class Plan:
    steps: List[Step] = field(default_factory=list)


class ContextWindow:
    def __init__(self, redis: Redis, ttl: int = 3600):
        self.redis = redis
        self.ttl = ttl

    def _key(self, session_id: str, tenant_id: str) -> str:
        safe = hashlib.sha256(f"{tenant_id}:{session_id}".encode()).hexdigest()
        return f"lumina:ctx:{safe}"

    async def load(self, session_id: str, user_id: str, tenant_id: str, limit: int = 12) -> List[Dict[str, Any]]:
        key = self._key(session_id, tenant_id)
        raw = await self.redis.lrange(key, -limit, -1)
        history = [json.loads(item) for item in raw if isinstance(item, (str, bytes))]
        history.append({"role": "system", "user_id": user_id, "tenant_id": tenant_id, "ts": time.time()})
        return history

    async def append(self, session_id: str, tenant_id: str, role: str, content: Dict[str, Any]) -> None:
        key = self._key(session_id, tenant_id)
        payload = json.dumps({"role": role, "content": content, "ts": time.time()})
        await self.redis.rpush(key, payload)
        await self.redis.expire(key, self.ttl)

    async def clear(self, session_id: str, tenant_id: str) -> None:
        await self.redis.delete(self._key(session_id, tenant_id))


class IntentRouter:
    """Zero-shot style intent classifier with rule-based fallback.
    In production this calls an embedding model or fine-tuned classifier.
    """

    KEYWORDS: Dict[Intent, List[str]] = {
        Intent.PASSWORD_RESET: ["password", "reset", "vpn", "token", "login", "locked"],
        Intent.PTO_BALANCE: ["pto", "vacation", "time off", "balance", "days off"],
        Intent.HR_POLICY: ["policy", "handbook", "benefits", "hr", "parental leave"],
        Intent.IT_SUPPORT: ["ticket", "software", "install", "laptop", "issue", "broken"],
        Intent.KNOWLEDGE_RETRIEVAL: ["doc", "document", "how to", "faq", "find", "wiki"],
    }

    def __init__(self, model_url: Optional[str] = None):
        self.model_url = model_url
        self.client: Optional[httpx.AsyncClient] = None

    async def classify(self, text: str) -> tuple[Intent, ConfidenceTier, float]:
        lower = text.lower()
        scores: Dict[Intent, int] = {intent: 0 for intent in Intent}
        for intent, keywords in self.KEYWORDS.items():
            scores[intent] = sum(1 for kw in keywords if kw in lower)
        best = max(scores, key=scores.get)  # type: ignore[arg-type]
        score = scores[best]
        if score >= 3:
            confidence = ConfidenceTier.HIGH
        elif score >= 1:
            confidence = ConfidenceTier.MEDIUM
        else:
            confidence = ConfidenceTier.LOW
        if confidence == ConfidenceTier.LOW:
            best = Intent.ESCALATE
        return best, confidence, min(score / 5.0, 0.99)


class ToolRegistry:
    def __init__(self) -> None:
        self._tools: Dict[str, Callable[[Dict[str, Any], PolicyContext], Coroutine[Any, Any, Any]]] = {}

    def register(self, tool_id: str, handler: Callable[[Dict[str, Any], PolicyContext], Coroutine[Any, Any, Any]]) -> None:
        self._tools[tool_id] = handler

    def resolve(self, tool_id: str) -> Callable[[Dict[str, Any], PolicyContext], Coroutine[Any, Any, Any]]:
        if tool_id not in self._tools:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"Tool {tool_id} not found")
        return self._tools[tool_id]

    async def execute(self, tool_id: str, params: Dict[str, Any], policy: PolicyContext) -> Any:
        handler = self.resolve(tool_id)
        return await handler(params, policy)


class Planner:
    """Rule-based planner. Replace with LLM planner for open-ended domains."""

    def generate(self, intent: Intent, context: List[Dict[str, Any]]) -> Plan:
        user_msg = next((m for m in reversed(context) if m.get("role") == "user"), {})
        text = (user_msg.get("content") or {}).get("text", "")
        steps: List[Step] = []
        if intent == Intent.PASSWORD_RESET:
            steps.append(Step("verify_identity", {"factor": "sso"}))
            steps.append(Step("reset_token", {"service": "vpn"}))
        elif intent == Intent.PTO_BALANCE:
            steps.append(Step("fetch_pto", {"user_id": (context[-1].get("content") or {}).get("user_id")}))
        elif intent == Intent.IT_SUPPORT:
            steps.append(Step("create_ticket", {"subject": text[:120], "queue": "l2"}))
        elif intent == Intent.HR_POLICY:
            steps.append(Step("search_kb", {"query": text}))
        else:
            steps.append(Step("escalate", {"reason": "low_confidence"}))
        return Plan(steps=steps)


class AuditLogger:
    async def log(self, tenant_id: str, user_id: str, intent: Intent, steps: List[Step], results: List[Any]) -> str:
        trace_id = hashlib.sha256(f"{tenant_id}:{user_id}:{time.time()}".encode()).hexdigest()[:16]
        logger.info(
            "audit trace=%s tenant=%s user=%s intent=%s steps=%s",
            trace_id, tenant_id, user_id, intent.value, [s.tool_id for s in steps]
        )
        return trace_id


async def get_redis() -> Redis:
    host = os.getenv("REDIS_HOST", "localhost")
    port = int(os.getenv("REDIS_PORT", "6379"))
    return Redis(host=host, port=port, decode_responses=True)


registry = ToolRegistry()
intent_router = IntentRouter(model_url=os.getenv("LUMINA_MODEL_URL"))
planner = Planner()
audit = AuditLogger()


async def verify_identity(params: Dict[str, Any], policy: PolicyContext) -> Dict[str, Any]:
    await asyncio.sleep(0.05)
    return {"verified": True, "factor": params.get("factor", "sso")}


async def reset_token(params: Dict[str, Any], policy: PolicyContext) -> Dict[str, Any]:
    await asyncio.sleep(0.08)
    token = hashlib.sha256(os.urandom(32)).hexdigest()[:24]
    return {"token": token, "expires_in": 900, "service": params.get("service", "vpn")}


async def fetch_pto(params: Dict[str, Any], policy: PolicyContext) -> Dict[str, Any]:
    await asyncio.sleep(0.06)
    return {"remaining_days": 14, "pending_requests": 1, "user_id": params.get("user_id")}


async def create_ticket(params: Dict[str, Any], policy: PolicyContext) -> Dict[str, Any]:
    await asyncio.sleep(0.1)
    ticket_id = f"TKT-{int(time.time() * 1000) % 100000}"
    return {"ticket_id": ticket_id, "queue": params.get("queue", "l1"), "subject": params.get("subject", "")}


async def search_kb(params: Dict[str, Any], policy: PolicyContext) -> Dict[str, Any]:
    await asyncio.sleep(0.07)
    return {"results": [{"title": "Remote Work Policy", "url": "/kb/remote-work"}], "query": params.get("query", "")}


async def escalate(params: Dict[str, Any], policy: PolicyContext) -> Dict[str, Any]:
    return {"escalated": True, "reason": params.get("reason", "manual"), "team": "human_support"}


registry.register("verify_identity", verify_identity)
registry.register("reset_token", reset_token)
registry.register("fetch_pto", fetch_pto)
registry.register("create_ticket", create_ticket)
registry.register("search_kb", search_kb)
registry.register("escalate", escalate)


class Orchestrator:
    def __init__(self, redis: Redis) -> None:
        self.context = ContextWindow(redis)

    async def handle(self, req: UserRequest, policy: PolicyContext) -> Dict[str, Any]:
        session_id = req.session_id or hashlib.sha256(f"{req.user_id}:{time.time()}".encode()).hexdigest()[:16]
        await self.context.append(session_id, req.tenant_id, "user", {"text": req.text, "user_id": req.user_id, "channel": req.channel})

        intent, confidence, score = await intent_router.classify(req.text)
        if confidence == ConfidenceTier.LOW:
            intent = Intent.ESCALATE

        history = await self.context.load(session_id, req.user_id, req.tenant_id)
        plan = planner.generate(intent, history)

        results: List[Any] = []
        for step in plan.steps:
            if policy.require_approval and step.tool_id in {"reset_token", "create_ticket"}:
                results.append({"tool": step.tool_id, "status": "pending_approval"})
                continue
            result = await registry.execute(step.tool_id, step.params, policy)
            results.append(result)

        await self.context.append(session_id, req.tenant_id, "assistant", {"intent": intent.value, "results": results})
        trace_id = await audit.log(req.tenant_id, req.user_id, intent, plan.steps, results)

        return {
            "session_id": session_id,
            "intent": intent.value,
            "confidence": confidence.value,
            "score": round(score, 3),
            "steps": [{"tool": s.tool_id, "params": s.params} for s in plan.steps],
            "results": results,
            "trace_id": trace_id,
        }


orch_cache: Dict[str, Orchestrator] = {}


async def get_orchestrator(redis: Redis = Depends(get_redis)) -> Orchestrator:
    if "default" not in orch_cache:
        orch_cache["default"] = Orchestrator(redis)
    return orch_cache["default"]


async def verify_token(x_lumina_token: str = Header(...)) -> PolicyContext:
    if x_lumina_token != os.getenv("LUMINA_API_TOKEN", "dev-token"):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")
    return PolicyContext(roles=["authenticated"])


@app.get("/health")
async def health() -> Dict[str, str]:
    return {"status": "ok", "version": "3.0.0"}


@app.post("/api/v1/chat")
async def chat(
    req: UserRequest,
    policy: PolicyContext = Depends(verify_token),
    orchestrator: Orchestrator = Depends(get_orchestrator),
) -> Dict[str, Any]:
    try:
        return await orchestrator.handle(req, policy)
    except Exception as exc:
        logger.exception("chat_error")
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(exc)) from exc


@app.post("/api/v1/sessions/{session_id}/clear")
async def clear_session(
    session_id: str,
    tenant_id: str = Header(...),
    redis: Redis = Depends(get_redis),
) -> Dict[str, bool]:
    await ContextWindow(redis).clear(session_id, tenant_id)
    return {"cleared": True}


@app.get("/api/v1/intents")
async def list_intents() -> List[str]:
    return [i.value for i in Intent]


def stream_sse(data: Dict[str, Any]) -> str:
    return f"data: {json.dumps(data)}\n\n"


@app.post("/api/v1/chat/stream")
async def chat_stream(
    req: UserRequest,
    policy: PolicyContext = Depends(verify_token),
    orchestrator: Orchestrator = Depends(get_orchestrator),
) -> AsyncIterator[str]:
    result = await orchestrator.handle(req, policy)
    chunks = [
        {"type": "intent", "value": result["intent"]},
        {"type": "thought", "value": f"Executing {len(result['steps'])} tool steps"},
    ]
    for step, res in zip(result["steps"], result["results"]):
        chunks.append({"type": "tool", "tool": step["tool"], "result": res})
    chunks.append({"type": "done", "trace_id": result["trace_id"]})

    async def generator() -> AsyncIterator[str]:
        for chunk in chunks:
            yield stream_sse(chunk)
            await asyncio.sleep(0.1)

    return generator()


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", "8000")))
