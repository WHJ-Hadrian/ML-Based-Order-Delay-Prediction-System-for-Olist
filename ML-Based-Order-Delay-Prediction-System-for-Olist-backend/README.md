# Python Analytics Runtime

This Python project provides the online model-serving scaffold and dependency groups for separate Temporal worker pools. Worker activities and workflows are intentionally not implemented yet. It does not own orders, jobs, or model-lifecycle databases. The FastAPI app exposes only liveness, readiness, and metrics endpoints; model APIs will be added with their contracts.

Install the lightweight serving runtime with `pip install -r requirements.txt`. Install development/test tools with `pip install -e ".[test]"`. Optional dependency groups are available for `workers`, `analytics`, `features` (Feast with Redis), and `ml`; install only what a worker image needs.

Run locally with `python -m uvicorn app.main:app --reload --port 8090`. Health probes are available at `/health/live` and `/health/ready`; Prometheus metrics are exposed at `/metrics`.
