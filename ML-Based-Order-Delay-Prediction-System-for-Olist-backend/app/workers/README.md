# Python Worker Pools

Long-running work is dispatched through Temporal, not through HTTP requests. Keep separate worker deployments and task queues for dataset ingestion, model training, and batch prediction so CPU, memory, and accelerator limits can be scaled independently. Workflows pass job IDs and object-store URIs; file contents stay in object storage.

Worker activities will be added with their job contracts. This package intentionally does not expose fake task handlers before those contracts are implemented.
