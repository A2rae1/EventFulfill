# EventFulfill

**活动预约与票务履约平台 · Event booking and ticket fulfillment**

A Java backend project with limited-capacity bookings, FIFO waitlists, confirmation, simulated payment, ticket issuance and authorized one-time check-in.

**Stack:** Java17, Spring Boot, MyBatis-Plus, MySQL, Redis/Lua, Redisson, RocketMQ, XXL-JOB. Optional Gateway/Nacos, observability and Kubernetes labs remain included.

## Business and engineering

- Session row locks, conditional capacity updates and unique active claims protect allocation across confirmation, cancellation and expiration. MySQL remains authoritative.
- Transactional Outbox, publisher leases, idempotent consumption and audited dead-letter replay support recoverable asynchronous fulfillment.
- Cache Aside, Lua admission and Redisson protect hot reads and request admission; expiration compensation promotes eligible waiters.
- JWT/RBAC, explicit session authorization, JUnit, concurrency/failure probes and OpenTelemetry support validation and diagnosis.

## Layout

`event-platform/`: Java source, tests, Gateway and Vue demo. `infra/`: Java middleware, scheduler/governance/observability labs. `tools/probes/`: optional Java operational probes written in Python. `scripts/`: Java build, test and runtime entry points.

## Verify and run

PowerShell7, JDK17+ and Maven are required for default verification. Docker Desktop is needed for real middleware, and Python/uv only for optional operational probes.

```powershell
./scripts/verify.ps1
./scripts/init-local.ps1
./scripts/apps-build.ps1
./scripts/apps-start.ps1
# http://127.0.0.1:8090/
./scripts/apps-stop.ps1
```

The initializer generates ignored local secrets. Demo users are organizer/alice/bob/checker/operator; use the generated EVENT_DEMO_PASSWORD locally. Do not commit `.env` files. Default tests use H2; real middleware checks are opt-in and skipped checks are not claimed as passed integrations.

## Optional RepoLens integration

[RepoLens](https://github.com/A2rae1/RepoLens) is maintained in a separate repository. EventFulfill hosts its Java identity/task-control API and the optional Agent UI tab. Booking works without the Python worker; the Agent tab requires RepoLens. See [two-repository integration](docs/INTEGRATION.md). The Agent source is not bundled in this repository.

Payment is simulated, deployment is local, and performance evidence is not production traffic. Business implementation uses AI coding assistance; upstream frameworks retain their own licenses. See [verification scope](docs/VERIFICATION.md) and [third-party notices](docs/THIRD_PARTY_NOTICES.md).

## Review fixes

See [reliability fixes](docs/REVIEW_FIXES_2026-11-06.md) for current-read transactions, durable approval delivery, generation-separated retries, evidence precision and versioned evaluation metrics. Rebuild old container images before using this source. See [2026-11-07 verification](docs/FINAL_VERIFICATION_2026-11-07.md) for the rebuilt two-checkout acceptance and remaining boundaries.
