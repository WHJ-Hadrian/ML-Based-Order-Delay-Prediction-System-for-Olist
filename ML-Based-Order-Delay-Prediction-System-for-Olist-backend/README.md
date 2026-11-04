# Python Analytics Runtime

This Python project provides the model-serving runtime, a reusable tabular training engine, and the rule-execution worker runtime. The token-protected internal training endpoint fits a real scikit-learn pipeline and atomically persists its artifact with dataset-version and field lineage; the Java model-management service remains the user-facing authorization and registry boundary. The rule worker validates frozen workbook plans, resolves the READY dataset execution snapshot, compiles whitelisted operations to bounded read-only ClickHouse preview queries, and reports node/run status. It can claim queued PREVIEW runs with a renewable lease and fencing token; FULL materialization and Temporal workflows are still not implemented. This service does not own orders, jobs, or model-registry metadata.

Install the lightweight serving runtime with `pip install -r requirements.txt`. Install development/test tools with `pip install -e ".[test]"`. The reusable CPU training engine and its tests require `pip install -e ".[training,test]"`; optional dependency groups are also available for `workers`, `analytics`, `features` (Feast with Redis), and `ml`. Install only what a worker image needs.

In Compose, set the same randomly generated `MODEL_TRAINING_INTERNAL_TOKEN` for `model-management-service` and `model-serving`. The authenticated Java model-management API accepts `POST /api/v1/models/training-runs`, reads only the caller's READY dataset version, runs a bounded scikit-learn training job, and stores the model version and lineage in `olist_models`. The private Python endpoint `POST /internal/v1/model-training-runs` rejects requests without the internal token. Its owner-scoped pipeline artifacts are stored in the persistent `model-training-artifacts` volume. The Java registry API lists the resulting versions at `GET /api/v1/models/versions`.

Run locally with `python -m uvicorn app.main:app --reload --port 8090`. Health probes are available at `/health/live` and `/health/ready`; Prometheus metrics are exposed at `/metrics`.

Validate a frozen rule-run plan without starting it:

```powershell
python -m app.workers.rule_execution --run-id <run-uuid> --validate-only --base-url http://localhost:8082
```

See `app/workers/README.md` and `../docs/rule-workbook/worker-execution.md` for the queue-consumer commands, execution boundaries, and remaining materialization/Temporal work.
