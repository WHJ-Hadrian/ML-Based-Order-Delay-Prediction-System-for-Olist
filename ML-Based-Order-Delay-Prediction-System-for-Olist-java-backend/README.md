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

### Local standalone run

The `local` profile disables Keycloak, stores metadata in a file-backed H2 database and
writes objects under `./data/datasets`:

```powershell
.\mvnw.cmd -pl dataset-service -am -DskipTests package
java -jar dataset-service/target/dataset-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

Health: `http://localhost:8082/actuator/health`
