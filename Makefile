.DEFAULT_GOAL := help
SHELL := /bin/sh
.NOTPARALLEL:

MVNW ?= ./mvnw
MAVEN_ARGS ?= -B -ntp
ENV_FILE ?= .env
PROJECT_NAME ?= online-reader
SECRET_MODE ?= staged
COMPOSE = docker compose --file compose.yaml --env-file "$(ENV_FILE)" --project-name "$(PROJECT_NAME)"

.PHONY: help env-file setup up down logs run build typecheck lint test test-integration check clean secret-check

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

typecheck: ## Install pinned frontend tools/dependencies and run strict tsc
	$(MVNW) $(MAVEN_ARGS) frontend:install-node-and-npm frontend:npm@npm-ci frontend:npm@typecheck

lint: ## Install pinned frontend tools/dependencies and run ESLint
	$(MVNW) $(MAVEN_ARGS) frontend:install-node-and-npm frontend:npm@npm-ci frontend:npm@lint

test: ## Run frontend checks and isolated Java tests without Docker
	$(MVNW) $(MAVEN_ARGS) test

test-integration: ## Run all checks including disposable PostgreSQL and Mailpit
	$(MVNW) $(MAVEN_ARGS) -Ppostgres-it,mailpit-it verify

check: ## Clean build: typecheck, lint, bundles, Java tests and executable JAR
	$(MVNW) $(MAVEN_ARGS) clean verify

secret-check: ## Check scanner fixtures and staged changes (CI: SECRET_MODE=snapshot)
	sh test/security/check-secrets.sh
	sh scripts/check-secrets.sh "$(SECRET_MODE)"

clean: ## Remove Maven target only; preserve env, dependencies, DB and books
	$(MVNW) $(MAVEN_ARGS) clean
