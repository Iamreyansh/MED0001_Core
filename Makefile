# MED0001 Core — common developer commands
# Usage: make <target>   |   make help

SHELL := /bin/bash
.DEFAULT_GOAL := help

ROOT        := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
COMPOSE     := podman compose -f $(ROOT)/docker-compose.yml
GRADLE      := $(ROOT)/gradlew
# Local: --no-daemon (no leftover JVM). CI=true (GitHub Actions): allow daemon + cache reuse.
ifeq ($(CI),true)
GRADLE_FLAGS ?=
else
GRADLE_FLAGS ?= --no-daemon
endif
PROFILE     ?= podman
API_PORT    ?= 8080
HEALTH_URL  := http://localhost:$(API_PORT)/api/v1/health
TF_STAGING  := $(ROOT)/infra/terraform/stacks/staging
TF_PROD     := $(ROOT)/infra/terraform/stacks/prod
ENV         ?= staging

.PHONY: help
help: ## Show available targets
	@awk 'BEGIN {FS = ":.*##"; printf "\n\033[1mMED0001 Core\033[0m\n\n"} \
		/^[a-zA-Z0-9_.-]+:.*?##/ { printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2 } \
		/^##@/ { printf "\n\033[1m%s\033[0m\n", substr($$0, 5) }' $(MAKEFILE_LIST)
	@echo ""

##@ Dependencies (Podman)

.PHONY: deps-up
deps-up: ## Start Postgres + Redis (Podman Compose)
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
test: ## Unit/integration tests (all modules)
	$(GRADLE) test $(GRADLE_FLAGS)

.PHONY: check
check: ## Full quality gates (JaCoCo 100%, Spotless, SpotBugs, ArchUnit)
	$(GRADLE) check -x dependencyCheckAnalyze $(GRADLE_FLAGS)

.PHONY: check-all
check-all: ## check + OWASP dependencyCheckAnalyze
	$(GRADLE) check $(GRADLE_FLAGS)

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
coverage: ## Run tests + JaCoCo verification (100%)
	$(GRADLE) test jacocoTestCoverageVerification $(GRADLE_FLAGS)

.PHONY: build
build: ## Compile all modules
	$(GRADLE) build -x test $(GRADLE_FLAGS)

.PHONY: jar
jar: ## Build API + worker boot jars
	$(GRADLE) :apps:api:bootJar :apps:worker:bootJar -x test $(GRADLE_FLAGS)

.PHONY: clean
clean: ## Clean Gradle + local run artifacts
	$(GRADLE) clean $(GRADLE_FLAGS)
	rm -rf $(ROOT)/.run $(ROOT)/infra/lambda/*.zip $(ROOT)/infra/lambda/build

##@ Lambda

.PHONY: package
package: ## Package api + worker Lambda zips
	chmod +x $(ROOT)/infra/lambda/package.sh
	$(ROOT)/infra/lambda/package.sh api
	$(ROOT)/infra/lambda/package.sh worker
	@test -f $(ROOT)/infra/lambda/api.zip && test -f $(ROOT)/infra/lambda/worker.zip \
		|| (echo "package: expected infra/lambda/{api,worker}.zip"; exit 1)
	@ls -lh $(ROOT)/infra/lambda/api.zip $(ROOT)/infra/lambda/worker.zip

.PHONY: package-api
package-api: ## Package API Lambda zip only
	chmod +x $(ROOT)/infra/lambda/package.sh
	$(ROOT)/infra/lambda/package.sh api

.PHONY: package-worker
package-worker: ## Package worker Lambda zip only
	chmod +x $(ROOT)/infra/lambda/package.sh
	$(ROOT)/infra/lambda/package.sh worker

##@ Terraform (local validate; apply via CI)

TF_ARGS ?=

.PHONY: tf-fmt
tf-fmt: ## terraform fmt -recursive
	terraform fmt -recursive $(ROOT)/infra/terraform

.PHONY: tf-fmt-check
tf-fmt-check: ## terraform fmt -check
	terraform fmt -check -recursive $(ROOT)/infra/terraform

.PHONY: tf-validate
tf-validate: ## Validate staging + prod stacks (no backend)
	@cd $(TF_STAGING) && terraform init -backend=false -input=false >/dev/null && terraform validate
	@cd $(TF_PROD) && terraform init -backend=false -input=false >/dev/null && terraform validate
	@rm -rf $(TF_STAGING)/.terraform $(TF_PROD)/.terraform \
		$(TF_STAGING)/.terraform.lock.hcl $(TF_PROD)/.terraform.lock.hcl
	@echo "Terraform validate OK (local artifacts removed)"

.PHONY: tf-plan
tf-plan: ## Plan stack ENV=staging|prod (needs AWS creds + remote state)
	@test "$(ENV)" = "staging" -o "$(ENV)" = "prod" || (echo "ENV must be staging or prod"; exit 1)
	cd $(ROOT)/infra/terraform/stacks/$(ENV) && rm -rf .terraform && terraform init -input=false && terraform plan -input=false $(TF_ARGS)

.PHONY: tf-unlock
tf-unlock: ## Force-unlock LOCK_ID=... ENV=staging|prod
	@test "$(ENV)" = "staging" -o "$(ENV)" = "prod" || (echo "ENV must be staging or prod"; exit 1)
	@test -n "$(LOCK_ID)" || (echo "LOCK_ID required"; exit 1)
	cd $(ROOT)/infra/terraform/stacks/$(ENV) && terraform init -input=false && terraform force-unlock -force "$(LOCK_ID)"

.PHONY: deploy
deploy: ## Upload lambda zips + terraform apply + publish live aliases ENV=staging|prod (CI)
	@test "$(ENV)" = "staging" -o "$(ENV)" = "prod" || (echo "ENV must be staging or prod"; exit 1)
	chmod +x $(ROOT)/scripts/deploy-stack.sh $(ROOT)/scripts/ci/publish-lambda.sh
	$(ROOT)/scripts/deploy-stack.sh $(ENV)

.PHONY: smoke-remote
smoke-remote: ## Poll HEALTH_URL until 200 + success/UP (deploy smoke)
	@test -n "$(HEALTH_URL)" || (echo "HEALTH_URL required"; exit 1)
	chmod +x $(ROOT)/scripts/smoke-remote.sh
	$(ROOT)/scripts/smoke-remote.sh "$(HEALTH_URL)"

.PHONY: scripts-syntax
scripts-syntax: ## bash -n on scripts/*.sh and scripts/ci/*.sh
	@for f in $(ROOT)/scripts/*.sh $(ROOT)/scripts/ci/*.sh; do \
		echo "bash -n $$f"; bash -n "$$f"; \
	done
	@echo "Script syntax OK"

##@ Bootstrap / verify

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
