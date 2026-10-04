# Reserve Service

Generic reservation microservice built with Spring Boot 3, Java 17, PostgreSQL, Redis, Flyway, and Spring Data JPA (Hibernate 6).

---

## Docker & Compose Setup

### Prerequisites
* Docker & Docker Compose v2+
* Copy `.env.example` to `.env` if custom ports or credentials are desired:
  ```bash
  cp .env.example .env
  ```

### Docker Compose Commands

| Action | Command | Makefile Shortcut |
| :--- | :--- | :--- |
| **Build images** | `docker compose build` | `make build` |
| **Start everything** | `docker compose up -d` (or `docker compose up --build`) | `make up` |
| **View app logs** | `docker compose logs -f app` | `make logs` |
| **Stop services** | `docker compose down` | `make down` |
| **Reset (delete volumes)** | `docker compose down -v` | `make reset` |

---

## Running Locally Against Compose Dependencies (Postgres & Redis)

If developing locally without running the Spring Boot application container:

1. **Start only PostgreSQL and Redis**:
   ```bash
   docker compose up -d postgres redis
   # or: make deps-up
   ```

2. **Run the Spring Boot application locally**:
   * **Windows (PowerShell)**:
     ```powershell
     $env:DB_PASSWORD="postgres"
     .\gradlew.bat bootRun
     ```
   * **Linux / macOS (Bash)**:
     ```bash
     DB_PASSWORD=postgres ./gradlew bootRun
     ```
   * *Note: When running locally without active profiles, the `dev` profile is active by default with DEBUG web logging and verbose errors.*

3. **Stop dependency containers when finished**:
   ```bash
   docker compose stop postgres redis
   # or: make deps-down
   ```

---

## Verification

Once the service is running (at `http://localhost:8080`):

1. **Health Check (Actuator)**:
   ```bash
   curl -i http://localhost:8080/actuator/health
   ```
   *Expected Response:* `200 OK` with `{"status":"UP"}`.

2. **Create a Resource**:
   ```bash
   curl -i -X POST http://localhost:8080/api/resources \
     -H "Content-Type: application/json" \
     -d '{
       "name": "Meeting Room 101",
       "type": "CONFERENCE_ROOM",
       "metadata": {"capacity": 10}
     }'
   ```
   *Expected Response:* `200 OK` (or `201 Created`).

3. **Create a Slot**:
   ```bash
   curl -i -X POST http://localhost:8080/api/resources/<RESOURCE_UUID>/slots \
     -H "Content-Type: application/json" \
     -d '{
       "startTime": "2026-10-05T09:00:00Z",
       "endTime": "2026-10-05T10:00:00Z",
       "capacity": 5
     }'
   ```
   *Expected Response:* `201 Created` with `Location: /api/resources/<RESOURCE_UUID>/slots/<SLOT_UUID>`.

---

## API & Postman Collection

A complete Postman collection (v2.1.0) and local environment file are provided in [`docs/postman/`](docs/postman/):

* **Collection**: [`docs/postman/reserve.postman_collection.json`](docs/postman/reserve.postman_collection.json)
* **Environment**: [`docs/postman/local.postman_environment.json`](docs/postman/local.postman_environment.json)
* **Usage Guide**: Refer to [`docs/postman/README.md`](docs/postman/README.md) for import instructions, variable configuration, automated test scripts, and concurrency verification.
