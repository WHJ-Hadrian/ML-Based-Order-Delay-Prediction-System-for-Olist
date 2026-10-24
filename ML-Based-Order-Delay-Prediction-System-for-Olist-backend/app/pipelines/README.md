# Analytics Pipelines

PySpark batch pipelines read versioned data from the Iceberg lakehouse and write derived datasets back to the lakehouse. Flink streaming jobs consume Kafka events and publish online features and analytical aggregates. Spark and Flink are compute runtimes; they are launched and monitored by the workflow layer rather than embedded in the FastAPI process.
