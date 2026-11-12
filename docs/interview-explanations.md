# ShopSphere — Interview Explanation Notes

Running notes: for every major implementation, the "why", alternatives,
failure modes, and how to explain it in a Senior Backend interview.

---

## Part 1 — Architecture, Technology Choices & Service Responsibilities
### 1.1 System Overview
                        ┌─────────────────────────────┐
                        │        React SPA (web)      │
                        │   nginx-served in Docker    │
                        └──────────────┬──────────────┘
                                       │ HTTPS/HTTP
                        ┌──────────────▼──────────────┐
                        │      API Gateway            │
                        │   (Spring Cloud Gateway)    │
                        │   JWT validation · CORS ·   │
                        │   routing · rate limits     │
                        └──┬─────┬─────┬─────┬────┬───┘
              ┌────────────┘     │     │     │     └────────────┐
              ▼                  ▼     ▼     ▼                  ▼
        ┌──────────┐      ┌────────┐ ┌──────┐ ┌──────────┐ ┌──────────────┐
        │  User    │      │Product │ │ Cart │ │  Order   │ │  Inventory   │
        │ Service  │      │Service │ │Svc   │ │  Service │ │  Service     │
        │PostgreSQL│      │ MySQL  │ │MySQL │ │PostgreSQL│ │    MySQL     │
        └────┬─────┘      └───┬────┘ └──┬───┘ └────┬─────┘ └──────┬───────┘
             │                │         │          │              │
             │         ┌──────▼─────────▼──────────▼──────────────▼───┐
             │         │            Apache Kafka                    │
             │         │  order.created · inventory.reserved ·       │
             │         │  payment.completed · order.cancelled · ...  │
             │         └──────────────────┬───────────────────────────┘
             │                            │
             │                  ┌─────────▼─────────┐   ┌──────────────┐
             │                  │ Payment Service   │   │ Notification │
             │                  │  (simulated PSP)  │   │   Service    │
             │                  │   PostgreSQL      │   │  (no DB)     │
             │                  └───────────────────┘   └──────────────┘
             │
             └── Redis (JWT refresh sessions, product cache, rate limiting)


### 1.2 Key Architectural Decisions (and the "why" an interviewer will probe)

| Decision | Choice | Why | Alternative rejected |
|---|---|---|---|
| Inter-service comms | **Synchronous REST (WebClient) + async Kafka events** | Checkout needs immediate consistency for reserve→pay→confirm; events handle decoupled downstream reactions | gRPC adds infra complexity; pure async makes checkout UX hard |
| Consistency model | **Saga (choreography via Kafka) + Transactional Outbox** | Distributed tx (2PC) doesn't scale; choreography keeps services loosely coupled | Orchestration adds a central coordinator bottleneck — we'll discuss both, implement choreography |
| Service discovery | **Static config in Compose → K8s DNS later** | Honest, simpler; Spring Cloud discovery is overkill at this scale | Eureka/Nacos adds ops burden |
| Auth | **JWT access token (15 min) + refresh token (opaque, stored in Redis)** | Stateless gateway validation; refresh revocation possible | Session cookies don't fit microservices |
| DB per service | MySQL ×3, PostgreSQL ×3 | Matches your stated stack; exercises both dialects | Shared DB = distributed monolith anti-pattern |
| Caching | Redis for products, cart reads, refresh tokens | Real invalidation patterns to discuss | Caffeine only — doesn't survive multi-pod |
| Messaging | Kafka with JSON + outbox + DLQ + idempotent consumers | Real-world at Wayfair scale | RabbitMQ — fine, but Kafka is on your stack and interview list |

### 1.3 Service Responsibilities & Database Ownership

| Service | Port | DB | Owns | Communicates via |
|---|---|---|---|---|
| `api-gateway` | 8080 | — | Routing, JWT filter, rate limit | routes to all |
| `user-service` | 8081 | PostgreSQL `userdb` | users, roles, refresh tokens | REST (login); issues JWT |
| `product-service` | 8082 | MySQL `productdb` | products, categories, search | REST reads; publishes `product.*` |
| `cart-service` | 8083 | MySQL `cartdb` | carts, cart items | REST to product-service; publishes `cart.*` |
| `order-service` | 8084 | PostgreSQL `orderdb` | orders, order items, saga state | REST + Kafka (orchestrates saga) |
| `payment-service` | 8085 | PostgreSQL `paymentdb` | payments, transactions, refunds | Kafka consumer/producer |
| `inventory-service` | 8086 | MySQL `inventorydb` | stock, reservations | REST + Kafka |
| `notification-service` | 8087 | none | email/SMS simulation | Kafka consumer only |

### 1.4 The Checkout Saga (choreography)

1. POST /orders (order-service)
   └─ order PENDING → publish order.created (via outbox)
2. inventory-service consumes order.created
   └─ pessimistic lock stock → reserve → publish inventory.reserved (or inventory.failed)
3. payment-service consumes inventory.reserved
   └─ idempotent charge → publish payment.completed (or payment.failed)
4. order-service consumes those
   └─ payment.completed → CONFIRMED; inventory.failed/payment.failed → CANCELLED + compensation events
5. Every state change publishes domain events → notification-service emails customer



### 1.5 Complete Repository Folder Structure

commercehub/
├── pom.xml                          # parent POM (Spring Boot 3.3.x, Java 17)
├── README.md
├── docker-compose.yml
├── .env.example
├── docs/
│   ├── architecture.md
│   ├── database-schema.md
│   ├── api-contracts.md
│   ├── kafka-topics.md
│   └── interview-guide.md
├── deploy/
│   ├── k8s/  jenkins/  monitoring/
├── services/
│   ├── api-gateway/
│   ├── user-service/
│   ├── product-service/
│   ├── cart-service/
│   ├── order-service/
│   ├── payment-service/
│   ├── inventory-service/
│   └── notification-service/
└── web/                             # React frontend



Each service follows an identical hexagonal-ish layout:

<service>/
├── pom.xml
├── Dockerfile
├── src/main/java/com/commercehub/<svc>/
│   ├── <Svc>Application.java
│   ├── config/        # SecurityConfig, KafkaConfig, RedisConfig, OpenAPI, WebClient
│   ├── controller/    # REST endpoints (thin)
│   ├── service/       # business logic
│   ├── repository/    # Spring Data JPA
│   ├── entity/        # JPA entities
│   ├── dto/           # request/response records
│   ├── mapper/        # entity↔DTO
│   ├── exception/     # domain exceptions + GlobalExceptionHandler
│   ├── event/         # Kafka producers/consumers + event DTOs
│   └── client/        # WebClient callers to other services
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/  # Flyway V1__*.sql
└── src/test/java/...  # unit + MockMvc + Testcontainers
### 1.6 Version Baseline (locked, documented)

| Component | Version | Why |
|---|---|---|
| Java | 17 LTS | records, sealed types, virtual threads preview off |
| Spring Boot | 3.3.5 | stable, wide ecosystem |
| Spring Cloud | 2023.0.x | Gateway compatibility |
| Kafka client | 3.7.x | via Spring Kafka 3.2.x |
| MySQL | 8.4 | LTS |
| PostgreSQL | 16 | LTS |
| Redis | 7.4 | |
| React | 18.3 + Vite 5 | |
| Node | 20 LTS | |


- **Database-per-service**: each service owns its schema; no cross-service
  FKs or joins. Cost: no distributed joins → solved with APIs (sync reads)
  and events (async facts). *Explain as: autonomy + independent deployability.*
- **Saga choreography over orchestration**: the checkout saga is linear
  (reserve → pay → confirm), so event-driven choreography fits. Orchestration
  concentrates state in a coordinator — easier to trace, harder to scale.
  Say "it depends": choreography for loose coupling, orchestration when the
  workflow is complex/branchy.
- **Transactional outbox**: dual-write problem — DB commit and Kafka send
  can't be atomic. Outbox table written in the same tx; relay publishes after
  commit. Guarantees "order exists ⟺ event exists".
- **Static service URLs in Compose → K8s DNS**: honest simplicity; service
  discovery (Eureka/Nacos) is ops overhead we don't need at this scale.

## Part 2 — Database Design

- **No cross-service FKs**: logical BIGINT references only. FK across DBs
  creates distributed coupling and breaks independent deploys/backups.
- **Price snapshots** in `order_items`/`cart_items`: orders are historical
  facts; catalog prices change. Never re-join orders to live product price.
- **Optimistic (`@Version`) vs pessimistic (`FOR UPDATE`)**: optimistic for
  low-contention entities (products, orders); pessimistic ONLY for stock —
  overselling must be impossible, so we pay the contention cost there.
- **Constraints carry business rules**: `uk_carts_customer` (one cart),
  `uk_payments_idempotency` (no double charge), `chk_stock_nonneg`
  (`reserved <= on_hand`), Postgres `EXCLUDE` (one default address).
- **Partial indexes**: `outbox_events WHERE published_at IS NULL` keeps the
  relay poll O(pending) not O(history). Same trick for live refresh tokens.
- **DECIMAL for money, TIMESTAMPTZ for time**: floats can't represent
  currency; tz-aware timestamps prevent ordering bugs.

## Part 3 — Product Service

- **DTOs + mapper**: entities leak lazy proxies, internal fields
  (`costPrice` = margin), and couple API to schema. Manual mapper chosen
  for transparency; MapStruct is the production alternative.
- **@EntityGraph / JOIN FETCH**: lazy `images` in a loop = N+1 queries.
  Entity graph fetches in the same round-trip. `readOnly=true` tx skips
  dirty-checking on reads.
- **Soft delete** (`deletedAt`): orders reference products forever; hard
  delete corrupts history.
- **Whitelist sort params**: `Sort.by(userInput)` is an ORDER BY injection
  vector — map "price_asc" → known Sort objects only.
- **Async Kafka publish for product events**: informational events —
  fire-and-forget OK. Order/payment events get the outbox because losing
  them breaks the saga. *Trade-off articulation is the point.*
- **X-User-Roles header for admin authz**: gateway strips client copies and
  injects validated roles; service stays JWT-agnostic.

## Part 4 — User Service / Security

- **JWT + refresh rotation**: stateless access tokens scale horizontally;
  opaque refresh tokens (stored as SHA-256 hash) give revocation. Rotation
  on every use + reuse detection (revoked token presented → revoke ALL user
  tokens) is the OWASP/Auth0 pattern for stolen-token response.
- **Why hash refresh tokens**: DB leak ≠ credential leak.
- **BCrypt(10)**: adaptive hashing; cost factor rises with hardware.
- **EAGER roles / LAZY addresses**: fetch strategy follows access pattern —
  authorities needed every request, addresses only on profile endpoints.
- **Generic auth errors**: "Invalid email or password" prevents
  user-enumeration attacks.
- **jjwt 0.12 pins algorithm**: auto-selection caused WeakKeyException;
  `.signWith(key, Jwts.SIG.HS256)` makes the contract explicit.
- **IDOR prevention**: identity from JWT principal / gateway header, never
  from path or body params.

## Part 5 — Cart Service

- **RestClient not WebClient**: cart ops are request-response — we NEED the
  product data synchronously. Reactive adds complexity without benefit here.
- **Price snapshot at add-to-cart**: instant render, no per-row product
  calls; but it's display data only — order-service revalidates at checkout.
  *Say: "snapshot for UX, revalidation for correctness."*
- **One cart per customer via unique key**: `getOrCreate` is idempotent
  under concurrent requests — second insert fails cleanly.
- **Optimistic lock on cart**: concurrent add-to-cart → one wins, loser
  retries. Acceptable for cart; inventory uses pessimistic locks instead.
- **Downstream failure → 503**: `RestClientException` maps to
  SERVICE_UNAVAILABLE with a generic message — distinguishes "our problem"
  from "their problem" for clients and monitoring.
- **Timeouts on RestClient** (1.5s connect / 3s read): a slow
  product-service fails fast instead of exhausting Tomcat threads —
  circuit breaker (Resilience4j) is the next hardening step.

## Part 6 — Inventory Service

- **Pessimistic locking (`SELECT ... FOR UPDATE`)**: stock check-and-
  decrement must be atomic. Optimistic retry can't prevent oversell at the
  DB boundary under READ_COMMITTED. Cost: hot-SKU throughput ≈ serial —
  acceptable because per-product ordering is inherently serial anyway.
  *Rule of thumb: optimistic for low-contention, pessimistic only where
  correctness demands it.*
- **Validate-all-then-apply (two-phase)**: if ANY item lacks stock, NOTHING
  is reserved — no partial reservations leak. The failure path commits only
  the outbox event.
- **Reservation = lease, not ownership**: `expires_at` + sweeper returns
  stock if a saga stalls — stock can't be locked forever by a dead order.
- **Three-layer idempotency**: `processed_events` (event_id PK) +
  `(order,product,ACTIVE)` unique constraint + row-locked mutations.
  Dedupe insert shares the business transaction — a failed event is never
  marked processed, so Kafka redelivery retries cleanly.
- **Kafka consumer offset strategy**: `enable.auto.commit=false`,
  `AckMode.RECORD` + `@Transactional` → offset commits only after the DB
  tx commits. Crash mid-processing = redelivery = safe replay.
- **String payloads + ObjectMapper in listener**: explicit wire contract,
  no `__TypeId__` header coupling between independently-deployed services.
- **Outbox relay**: `@Scheduled` poll with PESSIMISTIC lock (safe during
  rolling deploys), per-event exponential backoff via `available_at`.
  At-least-once chosen deliberately: consumers dedupe, so duplicates are
  safe and lost events are not.
- **V2 migration, never edit V1**: Flyway checksums break if applied
  migrations change — schema evolution is append-only.

## Part 7 — Payment Service

- **PaymentGateway port + SimulatedPaymentGateway adapter**: hexagonal
  architecture — domain depends on the interface, PSP is swappable.
  Real Stripe/Adyen = new adapter, zero business-logic changes. This is
  the Adapter pattern + Dependency Inversion Principle in one move.
- **Exactly-once charge via idempotency key**: `"saga-" + orderId` is
  deterministic; UNIQUE constraint is the hard guarantee, the
  findByIdempotencyKey check is the fast path. Kafka redelivery or saga
  retry → returns existing payment, never double-charges.
- **Append-only ledger**: every AUTH/CAPTURE/REFUND is a new
  PaymentTransaction row — never UPDATE/DELETE. Same principle as
  double-entry bookkeeping; corrections are compensating entries.
- **Payment row + ledger + outbox in ONE transaction**: payment state and
  the saga event can never diverge — the dual-write problem solved by
  the outbox, again.
- **Gateway call inside the DB transaction — honest trade-off**: the PSP
  call (~150-500ms) holds a DB connection. Alternatives: call gateway
  first then write (risk: charged but unrecorded) — worse. Real systems
  add PSP-side idempotency + a reconciliation job for the residual edge.
  *Mention this nuance — it separates seniors from juniors.*
- **Retry uses a NEW idempotency key** (`-r<ts>`): the retry is a distinct
  charge attempt; the original failure stays in the ledger for audit.
- **Simulated PSP, never real card data**: `gateway_ref` is a token —
  storing PAN/CVV would put us in PCI-DSS scope. Failure rate and max
  charge are env-configurable for failure-injection testing.
- **Auto-refund on order.cancelled**: saga compensation — if the order
  dies after capture, money moves back automatically via the same
  ledger + outbox path.

## Part 8 — Order Service (saga orchestrator via choreography)

- **Why choreography, concretely**: order-service never calls
  inventory/payment synchronously. It writes order.created to the outbox
  and later reacts to their outcome events. No distributed locks, no
  service downtime coupling — each step's owner handles its own failure.
- **State machine on the entity**: `confirm()/cancel()/ship()/deliver()`
  throw on illegal transitions — business rules live in ONE place, not
  scattered across controllers. `sagaState` column = where the saga is,
  for debugging stuck orders.
- **Server-side cart fetch at checkout**: the client never sends items or
  prices — order-service pulls the cart via CartClient with the verified
  X-User-Id. Prevents price/quantity tampering; cart snapshots are
  revalidated downstream anyway.
- **Totals computed server-side**: tax 8%, free shipping ≥$50 — pricing
  logic is never client-trusted.
- **Compensation is just another event**: payment.failed → order CANCELLED
  + order.cancelled published → inventory releases stock, payment
  auto-refunds. Every step undoes itself; no central rollback coordinator.
- **Dedupe inside the same tx as the effect**: processed_events insert
  commits with the state change — a redelivered event can't re-fire
  confirm/cancel, and a failed event is never marked processed.
- **IDOR guard everywhere**: getById/track/cancel check
  `order.customerId == jwtUserId` — you can only touch YOUR orders.
- **Order number ≠ primary key**: human-facing ORD-YYYYMMDD-NNNNNN is
  unique but meaningless to guess; id stays internal.
- **Order item snapshots**: sku/name/unitPrice frozen at purchase —
  invoices are historical facts.
- **Order ↔ payment/inventory are logical refs only**: no cross-service
  FKs; consistency is eventual via events + compensations.

## Part 9 — Notification Service

- **DB-less by design**: notifications are ephemeral side-effects, not
  source-of-truth. Dedupe state lives in Redis (SETNX + 7-day TTL) instead
  of a database — proves you understand WHEN a service needs persistence.
- **Retry + DLQ**: `DefaultErrorHandler` + `FixedBackOff(2s, 3)` retries
  transient failures in-place; `DeadLetterPublishingRecoverer` routes
  exhausted records to `<topic>.DLT` — nothing silently dropped, ops can
  replay. This is the standard Kafka reliability pattern.
- **Redis dedupe vs DB dedupe**: SETNX is atomic and TTL-bounded — perfect
  for "have I seen this eventId?". DB would work too but adds a datastore
  the service doesn't otherwise need.
- **One listener per event type**: each handler is tiny and single-purpose;
  adding a new notification = new listener + new event, zero changes to
  existing code (Open/Closed principle).
- **At-least-once + idempotent consumer = effectively-once notification**:
  the customer never gets the same email twice even under Kafka redelivery.
- **Event payload minimalism**: notification records declare only fields
  they render; Jackson ignores extras → producers can add fields without
  breaking consumers (schema evolution).

## Part 10 — API Gateway

- **Edge auth, dumb services**: JWT is validated ONCE here; downstream
  services trust injected `X-User-Id`/`X-User-Roles`. Services stay fast
  and simple — no JWT parsing in every service.
- **Header stripping is the security crux**: the filter removes
  client-supplied identity headers BEFORE checking the token — otherwise
  anyone could send `X-User-Roles: ADMIN` and impersonate an admin. Test
  `spoofedHeaders_areStrippedEvenOnPublicPath` proves it.
- **GlobalFilter + Ordered(-100)**: runs before routing on every request;
  reactive (WebFlux) because gateway throughput is I/O-bound — blocking
  servlet stack would waste threads on proxying.
- **Public-path allowlist is explicit**: auth endpoints + product browse
  are public; `/admin/` is force-protected even under public prefixes —
  tested (`adminPath_requiresTokenEvenThoughProductPrefixIsPublic`).
- **Redis-backed rate limiting**: in-memory limiters multiply the limit by
  pod count — a classic scaling bug. Redis shares counters across gateway
  replicas. Key = client IP (auth userId would be better but runs later).
- **Route table in YAML, not code**: adding a service = config, not a
  redeploy of gateway logic. Path predicates are declarative and auditable.
- **401 at the edge, standard JSON error**: clients get consistent errors
  before any service is touched — cheaper and clearer than per-service 401s.
