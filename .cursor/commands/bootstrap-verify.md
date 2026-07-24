# Bootstrap verify

Local smoke prep: format, quality gates, Postgres/Redis up. Prefer the Makefile over raw Docker/Gradle.

## Prerequisites

- Java 21 on `PATH`
- Podman (compose provider)
- Make

## Run

```bash
make bootstrap-verify
# equivalent: make format && make check && make deps-up
```

Then smoke the API:

```bash
make start-bg    # profile=podman (Postgres + Redis)
make health      # GET http://localhost:8080/api/v1/health
```

Expected health body:

```json
{"success":true,"data":{"status":"UP"}}
```

H2-only shortcut (no Redis/Postgres): `make start PROFILE=local`.

## Done when

- Spotless / JaCoCo / ArchUnit / SpotBugs green (`make check`)
- `make health` returns HTTP 200 with the success envelope

## If it fails

- Fix coverage/ArchUnit/SpotBugs before opening a PR
- Deps issues: `make deps-ps`, `make deps-logs`, or `make deps-reset`
- Tear down: `make down`
