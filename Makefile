# MED0001 Core — common developer commands
# Usage: make <target>   |   make help

SHELL := /bin/bash
.DEFAULT_GOAL := help

ROOT        := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
COMPOSE     := podman compose -f $(ROOT)/docker-compose.yml
GRADLE      := $(ROOT)/gradlew
# Optional local secrets. .env is gitignored; use KEY=value syntax.
-include $(ROOT)/.env
export MEDMATE_MAPS_GEOCODE_API_KEY
# Local: --no-daemon (no leftover JVM). CI=true: allow daemon + cache reuse.
ifeq ($(CI),true)
GRADLE_FLAGS ?=
else
GRADLE_FLAGS ?= --no-daemon
endif
PROFILE     ?= podman
API_PORT    ?= 8080
HEALTH_URL  := http://localhost:$(API_PORT)/api/v1/health

.PHONY: help
help: ## Show available targets
	@awk 'BEGIN {FS = ":.*##"; printf "\n\033[1mMED0001 Core\033[0m\n\n"} \
		/^[a-zA-Z0-9_.-]+:.*?##/ { printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2 } \
		/^##@/ { printf "\n\033[1m%s\033[0m\n", substr($$0, 5) }' $(MAKEFILE_LIST)
	@echo ""

##@ Dependencies (Podman)

.PHONY: deps-up
deps-up: ## Start Postgres + Redis (Podman Compose)
	@podman machine inspect --format '{{.State}}' 2>/dev/null | grep -qx running || podman machine start
	$(COMPOSE) up -d
	@echo "Waiting for Postgres..."
	@for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do \
		podman exec med0001_core-postgres-1 pg_isready -U medmate -d medmate >/dev/null 2>&1 && break; \
		sleep 1; \
	done
	@podman exec med0001_core-redis-1 redis-cli ping
	@$(COMPOSE) ps

.PHONY: deps-down
deps-down: ## Stop Postgres + Redis
	$(COMPOSE) down

.PHONY: deps-logs
deps-logs: ## Tail dependency logs
	$(COMPOSE) logs -f

.PHONY: deps-ps
deps-ps: ## Show dependency container status
	$(COMPOSE) ps

.PHONY: deps-reset
deps-reset: ## Destroy volumes and recreate Postgres + Redis
	$(COMPOSE) down -v
	$(MAKE) deps-up

##@ Application

.PHONY: start
start: deps-up ## Start API (profile=$(PROFILE), default podman)
	$(GRADLE) :apps:api:bootRun --args='--spring.profiles.active=$(PROFILE)'

.PHONY: start-bg
start-bg: deps-up ## Start API in background (logs: make logs)
	@mkdir -p $(ROOT)/.run
	@nohup $(GRADLE) :apps:api:bootRun --args='--spring.profiles.active=$(PROFILE)' \
		> $(ROOT)/.run/api.log 2>&1 & echo $$! > $(ROOT)/.run/api.pid
	@echo "API starting (pid $$(cat $(ROOT)/.run/api.pid)). Logs: .run/api.log"
	@$(MAKE) --no-print-directory wait-healthy

.PHONY: stop
stop: ## Stop background API started via make start-bg
	@if [ -f $(ROOT)/.run/api.pid ]; then \
		kill $$(cat $(ROOT)/.run/api.pid) 2>/dev/null || true; \
		pkill -f 'apps.api.ApiApplication' 2>/dev/null || true; \
		rm -f $(ROOT)/.run/api.pid; \
		echo "API stopped"; \
	else \
		pkill -f ':apps:api:bootRun' 2>/dev/null || true; \
		pkill -f 'apps.api.ApiApplication' 2>/dev/null || true; \
		echo "No pid file; attempted to stop bootRun/ApiApplication"; \
	fi

.PHONY: restart
restart: stop start-bg ## Restart background API

.PHONY: logs
logs: ## Tail background API logs
	@tail -f $(ROOT)/.run/api.log

.PHONY: health
health: ## GET /api/v1/health
	@curl -sS -w "\nHTTP %{http_code}\n" $(HEALTH_URL)

.PHONY: wait-healthy
wait-healthy: ## Poll health until 200 (or timeout)
	@for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do \
		code=$$(curl -sS -o /tmp/med0001-health.json -w "%{http_code}" $(HEALTH_URL) 2>/dev/null || echo 000); \
		if [ "$$code" = "200" ]; then cat /tmp/med0001-health.json; echo; exit 0; fi; \
		sleep 2; \
	done; \
	echo "Health check timed out"; cat /tmp/med0001-health.json 2>/dev/null || true; exit 1

.PHONY: run
run: start ## Alias for start (foreground)

##@ Quality / build

.PHONY: test
test: ## Unit tests (all modules)
	$(GRADLE) test $(GRADLE_FLAGS)

.PHONY: integration-test
integration-test: ## Integration tests (Testcontainers; apps with integrationTest source set)
	$(GRADLE) integrationTest $(GRADLE_FLAGS)

.PHONY: check
check: ## Full quality gates (unit + IT, JaCoCo 100%, Spotless, SpotBugs, ArchUnit)
	$(GRADLE) check -x dependencyCheckAnalyze $(GRADLE_FLAGS)

.PHONY: bruno-check
bruno-check: ## Validate Bruno collection + launch-scope acceptance matrix
	@test -f $(ROOT)/bruno/payments/initiate.bru
	@grep -q 'Idempotency-Key' $(ROOT)/bruno/payments/initiate.bru
	@grep -q 'paymentIdempotencyKey' $(ROOT)/bruno/payments/initiate.bru
	@test -f $(ROOT)/docs/requirements/acceptance-matrix.json
	@python3 $(ROOT)/scripts/acceptance-ac-gate.py
	@echo "Bruno + acceptance-matrix OK"

.PHONY: bruno-run
bruno-run: ## Execute Bruno against HEALTH_URL (set BRUNO_REQUIRED=1 to fail closed)
	@$(ROOT)/scripts/bruno-run.sh

.PHONY: check-all
check-all: bruno-check ## check + OWASP dependencyCheckAnalyze
	$(GRADLE) check dependencyCheckAnalyze $(GRADLE_FLAGS)

.PHONY: dependency-check
dependency-check: ## OWASP dependencyCheckAnalyze only
	$(GRADLE) dependencyCheckAnalyze $(GRADLE_FLAGS)

.PHONY: format
format: ## Apply Spotless formatting
	$(GRADLE) spotlessApply $(GRADLE_FLAGS)

.PHONY: format-check
format-check: ## Verify Spotless (no writes)
	$(GRADLE) spotlessCheck $(GRADLE_FLAGS)

.PHONY: coverage
coverage: ## Unit + integration tests + JaCoCo verification (100%)
	$(GRADLE) test integrationTest jacocoTestCoverageVerification $(GRADLE_FLAGS)

.PHONY: build
build: ## Compile all modules
	$(GRADLE) build -x test $(GRADLE_FLAGS)

.PHONY: jar
jar: ## Build API + worker boot jars
	$(GRADLE) :apps:api:bootJar :apps:worker:bootJar -x test $(GRADLE_FLAGS)

.PHONY: clean
clean: ## Clean Gradle + local run artifacts
	$(GRADLE) clean $(GRADLE_FLAGS)
	rm -rf $(ROOT)/.run

.PHONY: smoke-remote
smoke-remote: ## Poll HEALTH_URL until 200 + success/UP (deploy smoke)
	@test -n "$(HEALTH_URL)" || (echo "HEALTH_URL required"; exit 1)
	chmod +x $(ROOT)/scripts/smoke-remote.sh
	$(ROOT)/scripts/smoke-remote.sh "$(HEALTH_URL)"

.PHONY: scripts-syntax
scripts-syntax: ## bash -n on scripts/*.sh
	@for f in $(ROOT)/scripts/*.sh; do \
		echo "bash -n $$f"; bash -n "$$f"; \
	done
	@echo "Script syntax OK"

##@ Bootstrap / verify

.PHONY: hooks-install
hooks-install: ## Point git at .githooks (block main commits; make check on push)
	@chmod +x $(ROOT)/.githooks/pre-commit $(ROOT)/.githooks/commit-msg $(ROOT)/.githooks/pre-push
	git -C $(ROOT) config core.hooksPath .githooks
	@echo "Git hooks installed (core.hooksPath=.githooks)"

.PHONY: bootstrap-verify
bootstrap-verify: format check deps-up ## Format + check + bring up deps (local smoke prep)
	@echo "Bootstrap verify OK. Start API with: make start-bg && make health"

.PHONY: smoke
smoke: ## deps-up + start-bg + health
	$(MAKE) start-bg
	$(MAKE) health

.PHONY: up
up: smoke ## Alias: full local smoke (deps + API + health)

.PHONY: down
down: stop deps-down ## Stop API and dependencies

##@ Terraform (state in S3 only)

TF_STACK := $(ROOT)/infra/terraform/stacks/$(ENV)
ENV ?= staging
# AWS CLI "login" creds are not visible to Terraform — export env vars first.
TF := eval "$$(aws configure export-credentials --format env 2>/dev/null)" && terraform

.PHONY: tf-fmt
tf-fmt: ## terraform fmt -recursive
	terraform fmt -recursive $(ROOT)/infra/terraform

.PHONY: tf-fmt-check
tf-fmt-check: ## terraform fmt -check
	terraform fmt -check -recursive $(ROOT)/infra/terraform

.PHONY: tf-validate
tf-validate: ## init -backend=false + validate (no AWS, no local state)
	@for s in staging prod; do \
		echo "==> validate $$s"; \
		terraform -chdir=$(ROOT)/infra/terraform/stacks/$$s init -backend=false -input=false; \
		terraform -chdir=$(ROOT)/infra/terraform/stacks/$$s validate; \
	done

.PHONY: tf-init
tf-init: ## Init with S3 backend (locks in S3 under MED0001/)
	@$(TF) -chdir=$(TF_STACK) init -input=false

.PHONY: tf-plan
tf-plan: ## Plan against S3 state (ENV=staging|prod)
	@$(TF) -chdir=$(TF_STACK) plan -input=false $(TF_ARGS)

.PHONY: tf-apply
tf-apply: ## Apply stack (ENV=staging|prod). Creates real AWS spend.
	@$(TF) -chdir=$(TF_STACK) apply -input=false $(TF_ARGS)

.PHONY: tf-unlock
tf-unlock: ## Force-unlock LOCK_ID=… ENV=staging|prod
	@test "$(ENV)" = "staging" -o "$(ENV)" = "prod" || (echo "ENV must be staging or prod"; exit 1)
	@test -n "$(LOCK_ID)" || (echo "LOCK_ID required"; exit 1)
	@$(TF) -chdir=$(TF_STACK) force-unlock -force "$(LOCK_ID)"

.PHONY: docker-build
docker-build: jar ## Build local images (podman/docker; host arch)
	@if command -v podman >/dev/null 2>&1; then \
		podman build -f $(ROOT)/apps/api/Dockerfile -t med0001-api:local $(ROOT); \
		podman build -f $(ROOT)/apps/worker/Dockerfile -t med0001-worker:local $(ROOT); \
	else \
		docker build -f $(ROOT)/apps/api/Dockerfile -t med0001-api:local $(ROOT); \
		docker build -f $(ROOT)/apps/worker/Dockerfile -t med0001-worker:local $(ROOT); \
	fi

.PHONY: docker-push
docker-push: ## Build jars + push images to ECR (ENV=staging)
	chmod +x $(ROOT)/scripts/docker-push.sh
	$(ROOT)/scripts/docker-push.sh $(ENV)

.PHONY: deploy-ecs
deploy-ecs: ## Force ECS rolling deploy (ENV=staging|prod)
	chmod +x $(ROOT)/scripts/deploy-ecs.sh
	$(ROOT)/scripts/deploy-ecs.sh $(ENV)
