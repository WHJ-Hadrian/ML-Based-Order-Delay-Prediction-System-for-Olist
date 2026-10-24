# Kubernetes Deployment

`service` is a reusable Helm chart. Install each Java API and the Python model-serving container as its own release, with an independent image, resource request, replica range, and environment file. The default chart creates an HPA for stateless HTTP services.

Temporal Worker pools use the same Deployment template with `service.enabled: false`, `health.enabled: false`, and `queueAutoscaling.enabled: true`; also set `autoscaling.enabled: false`. Configure a Prometheus query for that worker's Temporal task queue before enabling the KEDA ScaledObject. Keep ingestion, training, and batch-prediction workers in separate releases so each queue can scale independently. Queue autoscaling only works after a worker/Temporal metrics exporter publishes the configured backlog metric.

The chart expects a Kubernetes cluster with metrics-server for HPA. Queue-based scaling additionally requires KEDA and the Prometheus metric to be available. Compose remains the local development environment; it is not used as a production scheduler.
