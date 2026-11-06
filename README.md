# EventFulfill & RepoLens

Two backend engineering projects in one runnable workspace: **event booking and ticket fulfillment**, and an **evidence-grounded agent for investigating open-source repositories**.

The projects share an authenticated Java task control plane, a Vue demo interface, and local deployment tooling. Keeping them together makes their integration reproducible.

## Projects

| Project | Purpose | Main technologies |
| --- | --- | --- |
| [EventFulfill](event-platform/) | Capacity-limited booking, FIFO waitlists, confirmation, simulated payment, ticket issuance and authorized check-in | Java 17, Spring Boot, MyBatis-Plus, MySQL, Redis/Lua, Redisson, RocketMQ, XXL-JOB |
| [RepoLens](repo-agent/) | Investigate a pinned GitHub commit, retrieve source evidence, request approval for constrained checks, and produce traceable reports | Python 3.12, FastAPI, LangGraph, DeepSeek, Elasticsearch, MCP, PostgreSQL, S3-compatible storage, Docker |

### EventFulfill: correctness before throughput

- Session row locking, conditional capacity updates and unique active claims protect allocation across confirmation, cancellation and expiration.
- Transactional Outbox, publishing leases, idempotent consumption and dead-letter replay handle asynchronous fulfillment. HTTP 202 means accepted, not allocated.
- Redis Cache Aside, Lua admission and Redisson protect hot reads and request admission; MySQL remains the source of truth for capacity.
- Expiration reconciliation releases held capacity and promotes waitlisted requests; JWT/RBAC and explicit session permissions protect one-time check-in.

### RepoLens: evidence and recoverable execution

- Repository snapshots, citations and indexes are scoped by repository and commit; BM25, vector and hybrid retrieval can be compared.
- LangGraph checkpoints, task leases and fenced updates support recovery, cancellation and approval-bound execution.
- Read-only, non-root, network-disabled runner containers enforce bounded recipes and resource/output limits. This is not a strong sandbox for arbitrary malicious code.
- MCP tools, scoped Skills, owner-isolated context, S3 report storage and resumable uploads support the research workflow.
- An 80-case, source-grounded development evaluation set covers evidence, tools, permissions and recovery. Dataset provenance and known limitations are included.

## Repository layout

```text
event-platform/   Java booking service, task control plane, Vue UI and Gateway
repo-agent/       Python agent, retrieval, tools, constrained runner and evaluation
infra/            Docker Compose, SQL initialization and optional infrastructure labs
scripts/          Configuration, builds, startup, bounded verification and diagnostics
docs/             Setup, architecture, verification scope and upstream notices
```

## Quick start

Use PowerShell 7 with JDK 17+ (CI uses 21), Maven, Python 3.12, uv, and Docker Desktop with a running engine.

```powershell
./scripts/init-local.ps1
uv sync --project repo-agent --frozen
./scripts/verify.ps1
./scripts/executor-build.ps1
./scripts/apps-build.ps1
./scripts/apps-start.ps1
```

Open **http://127.0.0.1:8090/**. The initializer generates local secrets in ignored `.env` files. Demo identities are `organizer`, `alice`, `bob`, `checker`, and `operator`; use the generated `EVENT_DEMO_PASSWORD` locally. Never commit those files.

Live model calls are **disabled by default**. To enable them, set your own `DEEPSEEK_API_KEY` and `LIVE_MODEL_ENABLED=true` in `repo-agent/.env`, and review the configured budget. No key is included in this repository.

```powershell
./scripts/apps-stop.ps1
```

This stops only the project's runtime groups and retains data volumes. See [setup and operation](docs/SETUP.md) for optional search, scheduling, observability and service-governance groups.

## Verification and limits

Run `./scripts/verify.ps1` for Python lint/tests and Java/Gateway builds without paid model calls. Real MySQL/Redis/MQ/S3/Postgres/Docker tests are opt-in; skipped tests are not counted as passed integrations.

The projects have been exercised locally with real middleware, model calls, UI workflows and recovery probes. [Verification scope](docs/VERIFICATION.md) distinguishes those results from the checks reproducible in default CI. This repository does not claim production traffic, real payment processing, universal repository reproduction or independently measured agent quality.

## Sources and licensing

Business code and integration tooling were developed with AI coding assistance on top of upstream frameworks and SDKs. Upstream software, bundled Vue, XXL-JOB SQL and Kubernetes manifests retain their respective licenses. See [third-party notices](docs/THIRD_PARTY_NOTICES.md). No blanket license is applied to third-party material.
