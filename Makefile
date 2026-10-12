.PHONY: env up down logs test test-java test-python test-web dev-web smoke sweep lint tf-validate

env:            ## generate .env with random secrets (first run only)
	./scripts/generate-env.sh

up: env         ## build and start the whole platform (http://localhost)
	docker compose up -d --build --wait --wait-timeout 300
	@echo "Web app: http://localhost  |  API: http://localhost/api/v1  |  Swagger: http://localhost/swagger-ui.html"

down:           ## stop and remove containers and volumes
	docker compose down -v

logs:
	docker compose logs -f --tail=100

test: test-java test-python test-web

test-java:      ## unit + slice + Testcontainers integration tests (Docker required)
	cd core-service && mvn -B verify

test-python:
	cd analytics-service && pytest -q

test-web:       ## frontend lint + typecheck + unit tests
	cd frontend && npm run lint && npm run typecheck && npm test

dev-web:        ## Vite dev server with hot reload on http://localhost:5173 (API via the running stack)
	cd frontend && npm run dev

lint:
	cd core-service && mvn -B -q -DskipTests compile checkstyle:check spotbugs:check
	cd analytics-service && ruff check .

smoke:          ## end-to-end smoke test against the running stack
	set -a; . ./.env; set +a; BASE_URL=http://localhost CHECK_ANALYTICS=true \
	  ADMIN_EMAIL=$$BOOTSTRAP_ADMIN_EMAIL ADMIN_PASSWORD=$$BOOTSTRAP_ADMIN_PASSWORD python3 scripts/smoke_test.py

sweep:          ## broad API regression sweep (~240 calls, all roles) against the demo stack
	set -a; . ./.env; set +a; python3 scripts/api_sweep.py

tf-validate:
	docker run --rm -v "$$PWD/infra/aws/terraform":/tf -w /tf hashicorp/terraform:1.9 init -backend=false -input=false >/dev/null
	docker run --rm -v "$$PWD/infra/aws/terraform":/tf -w /tf hashicorp/terraform:1.9 validate
