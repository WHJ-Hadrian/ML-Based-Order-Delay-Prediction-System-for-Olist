# Python Analytics Runtime

This Python project provides the online model-serving scaffold, dependency groups for separate Temporal worker pools, and the rule-execution worker control-plane core. The rule worker validates frozen workbook plans, resolves the READY dataset execution snapshot, builds a deterministic topological order, and reports node/run status; it deliberately fails closed before starting a run until the ClickHouse backend exists. Temporal workflows and other worker activities are still not implemented. It does not own orders, jobs, or model-lifecycle databases. The FastAPI app exposes only liveness, readiness, and metrics endpoints; model APIs will be added with their contracts.

Install the lightweight serving runtime with `pip install -r requirements.txt`. Install development/test tools with `pip install -e ".[test]"`. Optional dependency groups are available for `workers`, `analytics`, `features` (Feast with Redis), and `ml`; install only what a worker image needs.

Run locally with `python -m uvicorn app.main:app --reload --port 8090`. Health probes are available at `/health/live` and `/health/ready`; Prometheus metrics are exposed at `/metrics`.

Validate a frozen rule-run plan without starting it:

```powershell
python -m app.workers.rule_execution --run-id <run-uuid> --validate-only --base-url http://localhost:8082
```

See `app/workers/README.md` and `../docs/rule-workbook/worker-execution.md` for execution boundaries and the remaining ClickHouse/Temporal work.
