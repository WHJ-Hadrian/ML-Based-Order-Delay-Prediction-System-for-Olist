# Java Services

This Maven reactor builds independently deployable Spring Boot services. `platform-runtime`
contains shared operational dependencies; business code lives in each service module.

## Build

Run from this directory on Windows:

```powershell
.\mvnw.cmd test
```

Run one service and its reactor dependencies:

```powershell
.\mvnw.cmd -pl order-service -am spring-boot:run
```

The apps use isolated in-memory H2 databases by default. Docker Compose overrides each
datasource with its own MySQL database.

## Dataset service

`dataset-service` owns the dataset library. Its code is organized around explicit layers:

```text
controller/     HTTP endpoints
service/        use-case interfaces
service/impl/   transaction and business logic
mapper/         MyBatis mapper interfaces
entity/         database rows
dto/            inbound request objects
vo/             outbound response objects
convert/        entity-to-VO conversion
profiler/       CSV / TSV / ZIP / XLSX table profiling
storage/        local filesystem and S3 implementations
exception/      business exceptions and global error handling
```

Database schema is managed by Flyway under `src/main/resources/db/migration`. SQL mappings
live in `src/main/resources/mapper/*.xml`.

### API

```text
POST   /api/v1/datasets                  JSON: create an empty dataset
PATCH  /api/v1/datasets/{id}             JSON: rename / update description
POST   /api/v1/datasets/{id}/files       multipart: append a data file
POST   /api/v1/datasets                  multipart: legacy create + upload shortcut
GET    /api/v1/datasets/{id}             detail, including files and parsed tables
GET    /api/v1/datasets                  list / filter / paginate
```

List query parameters: `keyword`, `format`, `status`, `hasData`, `page`, `size`.

If a create request has no name, the service allocates the next available `数据集N` name.
CSV, TSV, ZIP and XLSX files are accepted up to 1 GB (XLSX up to 100 MB). CSV/TSV headers
define columns and XLSX sheets are counted as tables. ZIP archives are treated only as
container source files: their CSV / TSV / XLSX entries are parsed into logical tables, and
the archive itself is never registered as a data table. Table source names point to the
entry inside the archive.

## Model management service

`model-management-service` is the user-facing training and registry boundary. Training is
asynchronous: `POST /api/v1/models/training-runs` accepts an optional `Idempotency-Key`,
persists a `training_runs` row, and returns `202 Accepted` with a `Location` header. A
worker claims queued runs using a claim token and renewable lease, supports cancellation,
lease-expiry takeover, bounded retries, and heartbeat renewal. It validates the caller's
READY dataset catalog and sends Python only a `dataSource` descriptor; it never pages the
whole table through Java.

```text
POST   /api/v1/models/training-runs                 queue training (202)
GET    /api/v1/models/training-runs/{id}            poll status, stage and progress
GET    /api/v1/models/training-runs                 list owner-scoped runs
POST   /api/v1/models/training-runs/{id}/cancel     cancel queued/running work
GET    /api/v1/models/versions/{id}/manifest        owner-scoped manifest v2
GET    /api/v1/models/versions/{id}/artifact        stream owner-scoped ZIP bundle
POST   /api/v1/models/versions/{id}/approve         DRAFT -> APPROVED
POST   /api/v1/models/versions/{id}/deprecate       APPROVED -> DEPRECATED
```

Python streams selected columns from
`/internal/model-training/dataset-versions/{versionId}/tables/{tableId}/export.csv` using
`X-Model-Training-Token`. That endpoint caps the export at 256 MB, honors `maxRows`, and
rejects missing/duplicate columns. The Python contract requires `dataSource.columns` to be
exactly the union of feature, target, and split columns.

Set the same `MODEL_TRAINING_INTERNAL_TOKEN` for `dataset-service`, `model-serving`, and
`model-management-service`. `MODEL_TRAINING_WORKER_BEARER_TOKEN` optionally points the
worker at a service account JWT accepted by `dataset-service`; without it, the worker uses
the caller token temporarily stored on the run and clears it on terminal states. Prefer a
service account JWT outside local development.

### Local standalone run

The `local` profile disables Keycloak, stores metadata in a file-backed H2 database and
writes objects under `./data/datasets`:

```powershell
.\mvnw.cmd -pl dataset-service -am -DskipTests package
java -jar dataset-service/target/dataset-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

Health: `http://localhost:8082/actuator/health`
