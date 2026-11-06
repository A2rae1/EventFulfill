# Setup and operation

Run commands from the repository root in PowerShell 7. Required tools: JDK 17+, Maven, Python 3.12, uv, and Docker with Linux containers. Native test runs do not require starting the full container stack.

1. Run `./scripts/init-local.ps1` to generate local-only credentials. Existing values are retained.
2. Run `uv sync --project repo-agent --frozen` and `./scripts/verify.ps1`.
3. Build the trusted runner with `./scripts/executor-build.ps1`; build applications with `./scripts/apps-build.ps1`.
4. Start with `./scripts/apps-start.ps1`; open http://127.0.0.1:8090/. Stop with `./scripts/apps-stop.ps1`.

The default agent image uses an FTS retrieval baseline. Elasticsearch/CPU embedding tests require `uv sync --project repo-agent --extra embedding --frozen`, the search group, and native `./scripts/agent-start.ps1 -Search`; keep a reachable Java control plane. Do not claim the default image includes embedding weights.

## Optional groups

| Group | Entry point |
| --- | --- |
| Core MySQL/Redis/RocketMQ | `./scripts/runtime.ps1 -Group core` |
| Full applications | `./scripts/runtime.ps1 -Group apps` |
| Elasticsearch/object storage | `./scripts/runtime.ps1 -Group search` |
| Observability | `./scripts/runtime.ps1 -Group observe` |
| Gateway/Nacos/two Java instances | `./scripts/runtime.ps1 -Group governance` |
| XXL-JOB reconciliation | `./scripts/runtime.ps1 -Group jobs` |
| Isolated single-node Gateway lab | `./scripts/k8s-start.ps1 -Metrics` |

Run groups separately on resource-constrained machines. Application startup stops this project's dedicated kind lab when needed; do not use global Docker prune, delete volumes or stop unrelated services. Tools such as k6/kind/kubectl and optional npm clients are needed only for their corresponding labs, not default unit tests.

## Local ports

Java/UI: 8090; Python: 8092; governance Gateway: 8080; second Java instance: 8093; MySQL: 13406; Redis: 16379; Postgres: 15432; S3 API/console: 19000/19001; Elasticsearch: 19200. Services bind to loopback for local development. This is not an internet-facing production configuration.

Model calls require your own credentials and can incur charges. Review `MODEL_BUDGET_CNY`; external billing and retries are not guaranteed exactly-once. Keep only one live-model writer for the shared local budget ledger at a time.

Demo payment/refund is simulated. Runner approval binds the plan, image identity, recipe and expiry. A successful syntax check is not equivalent to passing the upstream project's tests. Resume-upload UI probes take `UPLOAD_FIXTURE` or default to `.local/ui-resume.zip`; supply your own non-sensitive ZIP fixture.
