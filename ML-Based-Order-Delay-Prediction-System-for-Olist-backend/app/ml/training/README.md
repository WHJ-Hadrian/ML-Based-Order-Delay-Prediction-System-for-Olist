# Model Training

Training code is exposed through the internal Python model engine and orchestrated by the Java model-management service. The current engine trains scikit-learn pipelines, stores owner-scoped artifacts on the configured shared model-artifact volume, and registers dataset-version lineage, parameters, metrics, and samples in the model registry.

The model trial API evaluates one registered model against one READY dataset version and persists the result. The comparison API evaluates 2–5 same-task, same-target model versions against a single test-table read. Each trial stores a shared `comparisonId`; `GET /api/v1/models/comparisons/{id}` reopens the complete comparison. Only declared feature columns and, when evaluation is enabled, the common target column are sent to the model runtime.

Trial and comparison requests reject any test dataset whose dataset ID or version ID matches the training lineage of a selected model. This prevents accidental in-sample evaluation; a separate dataset is required until the platform has explicit row-level holdout lineage for datasets derived from a shared source.

The local artifact directory is not an MLflow store. Multi-replica deployments must mount the same durable artifact storage for the Python service; promoting and versioning registered artifacts through MLflow is not implemented yet.

## Product references

- KNIME's Analytics Platform guide documents building workflows from connected nodes. Fact from the product docs: workflows are composed and connected from nodes. Design inference here: compare several registered model versions against one frozen test-data selection and persist each result under one comparison ID, while keeping the platform's own data and permission model.
- Dataiku's machine-learning documentation groups model lifecycle, model exploration, and model-setting reusability topics. Fact from the documentation navigation and guides: evaluation/exploration and model lifecycle are distinct user activities. Design inference here: keep training registration separate from the independent trial workspace, and make completed comparisons reopenable without changing model artifacts.

References: [KNIME Analytics Platform User Guide](https://docs.knime.com/latest/analytics_platform_user_guide/index.html), [Dataiku Machine Learning](https://doc.dataiku.com/dss/latest/machine-learning/index.html), [Dataiku Supervised Machine Learning](https://doc.dataiku.com/dss/latest/machine-learning/supervised/index.html).
