.DEFAULT_GOAL := help
SHELL := /bin/sh
.NOTPARALLEL:

MVNW ?= ./mvnw
MAVEN_ARGS ?= -B -ntp
ENV_FILE ?= .env
PROJECT_NAME ?= online-reader
SECRET_MODE ?= staged
COMPOSE = docker compose --file compose.yaml --env-file "$(ENV_FILE)" --project-name "$(PROJECT_NAME)"

.PHONY: help env-file setup up down logs run build typecheck lint test test-integration check clean secret-check setup-e2e test-e2e test-acceptance test-performance

help: ## Show commands; no environment or services are changed
	@awk -F ':.*## ' '/^[a-zA-Z][a-zA-Z0-9_-]*:.*## / { printf "  %-18s %s\n", $$1, $$2 }' Makefile

env-file: ## Create a private local env only if absent; preserve an existing file
	@sh scripts/setup-local.sh "$(ENV_FILE)"

setup: env-file ## Prepare frontend tools/dependencies and start PostgreSQL/Mailpit
	$(MVNW) $(MAVEN_ARGS) frontend:install-node-and-npm frontend:npm@npm-ci
	$(COMPOSE) up -d --wait postgres mailpit

up: env-file ## Start PostgreSQL/Mailpit and wait for readiness
	$(COMPOSE) up -d --wait postgres mailpit

down: ## Stop the local stack; preserve PostgreSQL volumes and books
	$(COMPOSE) --profile app down

logs: ## Follow local service logs; may contain private runtime data
	$(COMPOSE) --profile app logs --follow

run: env-file ## Build/start the app container and services; wait for readiness
	$(COMPOSE) --profile app up -d --build --wait

build: ## Check frontend and package executable JAR; skip Java test execution
	$(MVNW) $(MAVEN_ARGS) -DskipTests package

setup-e2e: ## Install pinned browser tools and Chromium (Linux system libraries: see docs/quality.md)
	$(MVNW) $(MAVEN_ARGS) -Preader-e2e frontend:install-node-and-npm frontend:npm@browser-npm-ci frontend:npm@browser-install

test-e2e: ## Run critical Chromium journeys on owned PostgreSQL/Mailpit (Docker required)
	$(MVNW) $(MAVEN_ARGS) -Preader-e2e verify

test-acceptance: ## Run ordinary, PostgreSQL, Mailpit and Chromium checks together
	$(MVNW) $(MAVEN_ARGS) -Ppostgres-it,mailpit-it,reader-e2e verify

typecheck: ## Install pinned frontend tools/dependencies and run strict tsc
	$(MVNW) $(MAVEN_ARGS) frontend:install-node-and-npm frontend:npm@npm-ci frontend:npm@typecheck

lint: ## Install pinned frontend tools/dependencies and run ESLint
	$(MVNW) $(MAVEN_ARGS) frontend:install-node-and-npm frontend:npm@npm-ci frontend:npm@lint

test: ## Run frontend checks and isolated Java tests without Docker
	$(MVNW) $(MAVEN_ARGS) test

test-integration: ## Run all checks including disposable PostgreSQL and Mailpit
	$(MVNW) $(MAVEN_ARGS) -Ppostgres-it,mailpit-it verify

test-performance: ## Opt-in control rig: 10 users, 1000 books, app+DB limited to 2CPU/4GiB
	$(MVNW) $(MAVEN_ARGS) -Preader-performance verify

check: ## Clean build: typecheck, lint, bundles, Java tests and executable JAR
	$(MVNW) $(MAVEN_ARGS) clean verify

secret-check: ## Check scanner fixtures and staged changes (CI: SECRET_MODE=snapshot)
	sh test/security/check-secrets.sh
	sh scripts/check-secrets.sh "$(SECRET_MODE)"

clean: ## Remove Maven target only; preserve env, dependencies, DB and books
	$(MVNW) $(MAVEN_ARGS) clean
