# astro — top-level orchestrator
# Delegates to per-layer build systems (kernel/Makefile, Maven, Angular CLI, Python).

.PHONY: help dev dev-setup build test clean kernel services apps python cli docs paper \
        docker-up docker-down install-services run-sim run-sim-real run-sim-dev run-sim-mock run-export

help:
	@echo "astro — N-body stellar cluster simulator"
	@echo ""
	@echo "Targets:"
	@echo "  dev          Spin up postgres + services + frontends in dev mode"
	@echo "  dev-setup    Install build tools and download dependencies"
	@echo "  build        Compile kernel, services, frontends, python wrapper"
	@echo "  test         Run all test suites (kernel, services, apps, python, e2e)"
	@echo "  clean        Remove build artifacts everywhere"
	@echo ""
	@echo "Per-layer:"
	@echo "  kernel       Build the Fortran kernel only"
	@echo "  services     Build the Spring Boot services only"
	@echo "  apps         Build the Angular MFEs only"
	@echo "  python       Build the Python wrapper only"
	@echo "  cli          Build the CLI binary only"
	@echo "  docs         Build the Sphinx documentation site"
	@echo "  paper        Build the JOSS paper PDF"
	@echo ""
	@echo "Run individual services (mvn invoked in the right module):"
	@echo "  run-sim       Run simulation-service (real kernel; builds kernel + postgres needed)"
	@echo "  run-sim-real  Build kernel then run simulation-service (real, postgres needed)"
	@echo "  run-sim-dev   Build kernel then run simulation-service (real kernel, NO postgres) — Sem 3 dev"
	@echo "  run-sim-mock  Run simulation-service with --profile=mock (no kernel, no postgres)"
	@echo "  run-export    Run export-service"
	@echo ""
	@echo "Infra:"
	@echo "  docker-up    Start postgres via docker-compose"
	@echo "  docker-down  Stop postgres"

dev: docker-up
	./scripts/dev.sh

dev-setup:
	./scripts/dev-setup.sh

build: kernel services apps python cli

test:
	./scripts/test-all.sh

clean:
	$(MAKE) -C kernel clean
	cd services && mvn -q clean || true
	cd apps && rm -rf */dist */node_modules || true
	cd python && rm -rf build dist *.egg-info || true
	rm -rf docs-site/build paper/*.pdf

kernel:
	$(MAKE) -C kernel

services:
	cd services && mvn -q -DskipTests package

apps:
	cd apps/shell-app && npm run build
	cd apps/simulation-mfe && npm run build
	cd apps/export-mfe && npm run build

python:
	@cd python && \
	  if [ ! -d .venv ]; then echo "Creating python/.venv …"; python3 -m venv .venv; fi && \
	  ./.venv/bin/pip install -q --upgrade pip && \
	  ./.venv/bin/pip install -q -e .[dev,notebooks] && \
	  echo "Python wrapper installed in python/.venv (activate: source python/.venv/bin/activate)"

cli: python
	@echo "CLI 'nbody-sim' is provided by the python wrapper at python/.venv/bin/nbody-sim"

docs:
	$(MAKE) -C docs-site html

paper:
	$(MAKE) -C paper

docker-up:
	docker compose up -d

docker-down:
	docker compose down

install-services:
	cd services && mvn -q install -DskipTests

run-sim: install-services
	cd services/simulation-service && mvn spring-boot:run

run-sim-real: kernel install-services
	cd services/simulation-service && mvn spring-boot:run

run-sim-dev: kernel install-services
	cd services/simulation-service && mvn spring-boot:run -Dspring-boot.run.profiles=dev

run-sim-mock: install-services
	cd services/simulation-service && mvn spring-boot:run -Dspring-boot.run.profiles=mock

run-export: install-services
	cd services/export-service && mvn spring-boot:run
