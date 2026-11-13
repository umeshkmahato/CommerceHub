# CommerceHub — Enterprise E-Commerce Order Management Platform

A production-shaped microservices e-commerce backend built for Senior
Backend interview preparation. Real working code — not scaffolding.

## Architecture at a glance

```
React SPA ──► API Gateway :8080 ──► user :8081  (PostgreSQL, JWT/refresh)
                                ├─► product :8082 (MySQL, catalog+search+Redis)
                                ├─► cart :8083    (MySQL)
                                ├─► order :8084   (PostgreSQL, saga orchestrator)
                                ├─► payment :8085 (PostgreSQL, simulated PSP)
                                ├─► inventory :8086(MySQL, pessimistic locking)
                                └─► notification :8087 (Kafka-only, Redis dedupe)
Infra: MySQL 8.4 · PostgreSQL 16 · Kafka 7.7 (KRaft) · Redis 7.4
```

Checkout is a **Kafka choreography saga** with transactional outboxes:
`order.created → inventory.reserved → payment.completed → order.confirmed`,
with compensating events (`order.cancelled` → stock release + auto-refund).

## Stack

Java 17 · Spring Boot 3.3.5 · Spring Cloud Gateway · Spring Data JPA ·
Flyway · Spring Security (JWT) · Spring Kafka · Redis · Resilience4j ·
React 18 + Vite · MySQL 8.4 · PostgreSQL 16 · Docker Compose · K8s · Jenkins

## Quick start (Docker Compose — recommended)

```bash
cp .env.example .env
# set JWT_SECRET:  openssl rand -base64 48  →  paste into .env

docker compose up --build -d
```

Wait ~60s for Flyway migrations + Kafka topics, then:

- **Storefront**: http://localhost
- **Gateway API**: http://localhost:8080
- **Swagger**: http://localhost:8080/... (per-service :8081–8087/swagger-ui.html)
- **demo login**: demo@commercehub.com / Password123!
### Smoke test

```bash
# register + login
curl -sX POST localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"a@b.com","password":"Passw0rd","firstName":"A","lastName":"B"}'

# browse products (public)
curl -s 'localhost:8080/api/v1/products?size=5' | head
```

## Run backend locally (no Docker for apps)

```bash
# infra only
docker compose up -d mysql postgres kafka kafka-init redis

# build + run any service
cd services/product-service && mvn spring-boot:run
```

## Run tests

```bash
# per service (unit tests — no Docker needed)
cd services/order-service && mvn test

# integration tests need Docker (Testcontainers spins real MySQL/PG)
mvn verify
```

Current coverage: **51 unit tests** across all services (Mockito service
tests, MockMvc controller tests, gateway filter tests) + Testcontainers
repository ITs.

## Repository layout

```
services/           8 microservices, identical hexagonal layout each
  └── <svc>/src/main/java/com/commercehub/<pkg>/
      config/ controller/ service/ repository/ entity/ dto/ mapper/
      exception/ event/ client/   (+ security/ for user-service, gateway/ for payment)
web/                React 18 + Vite storefront
deploy/             k8s/ jenkins/ monitoring/ db-init scripts
docs/               architecture, schemas, kafka topics, monitoring, cicd,
                    kubernetes, interview-explanations, api-contracts
```

## Key design decisions (see docs/interview-explanations.md for depth)

- **Database-per-service** — no cross-service FKs; logical refs + events
- **Saga choreography + transactional outbox** — no 2PC, no lost events
- **Idempotent consumers** — `processed_events` / Redis SETNX on eventId
- **Pessimistic locking for stock** — overselling impossible by construction
- **JWT edge auth** — gateway validates once, injects X-User-Id/X-User-Roles
- **DTOs never entities** — costPrice/internal fields never cross the API

## Ports

| Service | Port | DB |
|---|---|---|
| api-gateway | 8080 | — |
| user-service | 8081 | PostgreSQL userdb |
| product-service | 8082 | MySQL productdb |
| cart-service | 8083 | MySQL cartdb |
| order-service | 8084 | PostgreSQL orderdb |
| payment-service | 8085 | PostgreSQL paymentdb |
| inventory-service | 8086 | MySQL inventorydb |
| notification-service | 8087 | none (Kafka+Redis) |
| web | 80 | — |

## Docs

`docs/` — database-schema, kafka-topics, monitoring, cicd, kubernetes,
interview-explanations (per-part "why" notes), api-contracts.
