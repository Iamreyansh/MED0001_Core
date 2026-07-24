# MED0001 Core — Namma MedMate API

Java 21 / Spring Boot 3 modular monolith for **Namma MedMate**, served at `core.api.nammamedmate.com`.

Day-to-day work goes through the root **`Makefile`** (Podman for Postgres/Redis). Run `make help` for the full list.

---

## Prerequisites

| Tool | Notes |
|------|--------|
| Java 21 | Temurin / JDK on `PATH` |
| Podman | Compose provider (`podman compose`) |
| Make | GNU make |
| (optional) Terraform ≥ 1.10, AWS CLI, `gh` | Infra / CI only |

---

## Quick start

```bash
make up        # start Postgres + Redis, API (background), wait for health
make health    # GET http://localhost:8080/api/v1/health
make down      # stop API + containers
```

Expected health response:

```json
{"success":true,"data":{"status":"UP"}}
```

Foreground API (logs in terminal):

```bash
make start                 # profile=podman (default)
make start PROFILE=local   # H2 in-memory, no Redis (tests/dev shortcut)
```

---

## Makefile cheat sheet

### Dependencies (Podman)

| Target | Description |
|--------|-------------|
| `make deps-up` | Start Postgres 16 + Redis 7 |
| `make deps-down` | Stop containers |
| `make deps-logs` | Tail container logs |
| `make deps-ps` | Container status |
| `make deps-reset` | Wipe volumes and recreate |

### Application

| Target | Description |
|--------|-------------|
| `make start` / `make run` | API foreground (`PROFILE=podman`) |
| `make start-bg` | API background → `.run/api.log` |
| `make stop` | Stop background API |
| `make restart` | Stop + start-bg |
| `make logs` | Tail API logs |
| `make health` | Curl `/api/v1/health` |
| `make wait-healthy` | Poll until HTTP 200 |
| `make up` / `make smoke` | deps + start-bg + health |
| `make down` | stop + deps-down |

Overrides: `PROFILE=podman|local`, `API_PORT=8080`.

### Quality / build

| Target | Description |
|--------|-------------|
| `make test` | All module tests |
| `make check` | JaCoCo **100%**, Spotless, SpotBugs, ArchUnit |
| `make check-all` | `check` + OWASP dependency-check |
| `make format` / `make format-check` | Spotless apply / verify |
| `make coverage` | Tests + JaCoCo verification |
| `make build` / `make jar` | Compile / boot jars |
| `make clean` | Gradle clean + `.run` + lambda zips |

### Lambda

| Target | Description |
|--------|-------------|
| `make package` | Build `infra/lambda/api.zip` + `worker.zip` |
| `make package-api` / `make package-worker` | One artifact |

### Terraform

State and locks live only in S3 (`s3://terraform-locks-105927215604/MED0001/`). Do not commit `.terraform/`, state, or lockfiles.

| Target | Description |
|--------|-------------|
| `make tf-fmt` / `make tf-fmt-check` | Format / check |
| `make tf-validate` | Validate staging + prod (no backend; cleans local `.terraform`) |
| `make tf-plan ENV=staging` | Plan against remote state (needs AWS creds) |

### Bootstrap

| Target | Description |
|--------|-------------|
| `make bootstrap-verify` | format + check + deps-up |

---

## Project layout

```text
apps/api, apps/worker     # composition roots (HTTP / SQS)
platform/*                # kernel, security, persistence, messaging, observability
domains/*                 # epic-aligned modules (see MODULE_MAP)
db/migration/             # single Flyway tree
infra/terraform/          # modules + stacks/staging|prod
infra/lambda/             # packaging script + zips
.cursor/                  # rules, skills, agents, commands
docs/requirements/        # product epics/stories
```

- Agent guide: [`AGENTS.md`](AGENTS.md)
- Epic → module map: [`docs/architecture/MODULE_MAP.md`](docs/architecture/MODULE_MAP.md)
- Infra epic: [`docs/architecture/EPIC-000-infrastructure.md`](docs/architecture/EPIC-000-infrastructure.md)

---

## Config profiles

| Profile | Use |
|---------|-----|
| `podman` | Local Postgres + Redis via Compose ([`application-podman.yml`](apps/api/src/main/resources/application-podman.yml)) |
| `local` | H2 in-memory; Redis autoconfig off ([`application-local.yml`](apps/api/src/main/resources/application-local.yml)) |
| `staging` / `prod` | Deployed (Secrets Manager JWT, RDS Proxy, etc.) |

---

## CI / CD

| Workflow | Trigger |
|----------|---------|
| `quality-gates.yml` | Pull request → `main` |
| `deploy-main.yml` | Merge to `main` → staging → prod → GitHub Release |
| `terraform-force-unlock.yml` | Manual unlock of stuck TF lock |

Repo variable: `AWS_DEPLOY_ROLE_ARN` (OIDC). See [`docs/runbooks/github-setup.md`](docs/runbooks/github-setup.md).

---

## API conventions

- Base path: `/api/v1`
- Auth: `Authorization: Bearer <JWT>` (RS256)
- Envelope: `{ "success": true, "data": {...}, "meta": {...} }`
- Full product requirements: [`docs/requirements/INDEX.md`](docs/requirements/INDEX.md)
