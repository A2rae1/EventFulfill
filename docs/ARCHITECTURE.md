# Architecture

```text
Vue demo UI / API clients
  -> Spring Boot API: JWT/RBAC, bookings, capacity, task ownership and leases
      -> MySQL: business state, unique claims, Outbox and consumer receipts
      -> Redis: read cache, admission and rebuild coordination
      -> RocketMQ: asynchronous booking fulfillment and recovery
      -> FastAPI / LangGraph worker
          -> pinned GitHub snapshot and source evidence
          -> FTS or Elasticsearch retrieval / optional CPU embeddings
          -> DeepSeek structured tool decisions
          -> checkpoint store, S3-compatible reports and evidence
          -> explicit human approval -> constrained Docker recipe
```

## Booking lifecycle

Publish -> accept request -> allocate HELD or FIFO WAITING -> confirm free/simulated paid booking -> issue ticket -> authorized one-time check-in. Cancellation or expiration releases capacity and advances eligible waiters. A session row lock orders competing transitions. Conditional updates and a unique active entitlement protect capacity and duplicate booking invariants.

Outbox rows are persisted with business changes. Publisher leases permit retry after uncertain delivery; business consumption and the consumer receipt share a transaction. Replayed events retain the original event ID. Redis is not the final authority for allocation.

## Agent lifecycle

Authenticated request -> durable Java task -> worker claim/lease -> fixed repository commit -> evidence retrieval and scoped tool use -> approval if execution is requested -> bounded recipe -> citation-checked report. Checkpoints support restart; fencing rejects stale task writers. Ownership filters apply to tasks, evidence, context, uploads and reports.

Retrieval content/model caches can be reused across commits; per-version metadata is still rebuilt. A version becomes queryable only after all required index writes succeed. The agent never directly changes booking tables.

Optional Gateway/Nacos, observability and Kubernetes labs are isolated extensions rather than a requirement to run every service at once.
