# Java Services

This Maven reactor builds independently deployable Spring Boot services. Each app has its own entry point, port, database name, health endpoint, and container image. `platform-runtime` centralizes operational dependencies and servlet-service JWT policy; it contains no business models.

## Build

Run from this directory on Windows:

```powershell
.\mvnw.cmd test
```

Run one service and its reactor dependencies:

```powershell
.\mvnw.cmd -pl order-service -am spring-boot:run
```

The apps default to isolated in-memory H2 databases for local startup. Docker Compose overrides each datasource with its own PostgreSQL database. App routes are not implemented yet; only health, info, and Prometheus endpoints are exposed.
