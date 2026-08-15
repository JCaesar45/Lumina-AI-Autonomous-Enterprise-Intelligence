# Lumina AI Algorithm Design Notes

## 1. Intent Classification

The reference classifier uses a keyword-scoring heuristic for reproducibility. Each intent is associated with a small lexicon. The system scores the incoming message, selects the highest-scoring intent, and maps the score to a confidence tier. If confidence is low, the request is routed to `ESCALATE`.

Production replacement: fine-tuned BERT or an embedding similarity model against labeled examples.

## 2. Context Assembly

Sessions are stored in Redis as JSON lists keyed by `sha256(tenant_id:session_id)`. This gives us:
- Tenant isolation
- Automatic TTL expiry
- Sliding-window recall
- Low-latency reads

The context includes system metadata, recent messages, and tool results so that follow-up questions resolve pronouns and references.

## 3. Planning

The planner takes (intent, context) and returns an ordered list of `Step` objects. Each step names a tool and provides parameters. Dependencies are modeled but the reference implementation runs steps sequentially.

Future enhancement: DAG-based execution with parallel branches for independent steps.

## 4. Tool Execution

The registry maps tool IDs to handlers. Before execution, the policy context is checked. High-risk tools can require approval. Results are collected and appended to the context window for downstream reasoning.

## 5. Response Composition

The current implementation returns structured JSON. A production system would pass the assembled context to an LLM with a constrained response schema for natural-language generation.

## 6. Streaming

Server-Sent Events emit intent, thought, tool results, and completion markers. This gives users immediate feedback even when tool calls are slow.
