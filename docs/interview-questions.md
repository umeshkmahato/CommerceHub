# ShopSphere — Interview Q&A Guide

This guide is based on the current `commercehub` codebase, not only the architecture notes. Treat it as a study guide and a project walkthrough. Before an interview, run the project and be ready to show the source and tests; do not claim a capability is production-ready merely because a manifest or configuration file exists.

## 1. How to use this guide

Use the sections in this order:

1. **30-second pitch** — explain the system, your role, and why the design matters.
2. **Architecture and system design** — answer broad design questions before going into implementation details.
3. **Service-by-service technical Q&A** — know the code path, trade-off, and failure behavior for each service.
4. **Real-world scenarios** — practice incident response, capacity planning, data fixes, and production rollout questions.
5. **AI coding-agent best practices** — explain how to direct, review, test, and safely use an AI assistant.
6. **Coding challenges** — rehearse small, focused implementations without relying on generated code you cannot explain.
7. **Honesty checklist** — distinguish implemented behavior from planned hardening or missing verification.

### A concise project pitch

ShopSphere is an e-commerce platform built as independently deployable Spring Boot services behind an API gateway. It has a React storefront, service-specific MySQL or PostgreSQL databases, Kafka-based asynchronous workflows, Redis caching and rate limiting, container images, Kubernetes manifests, Jenkins automation, and monitoring configuration. The central checkout flow uses an event-driven saga: an order is created, inventory is reserved, payment is processed, and the order is confirmed or compensated through events. The implementation emphasizes database constraints, idempotency, transactional outboxes, retries, dead-letter handling, observability, and security at the trust boundary.

### Current implementation status to state accurately

- Backend modules exist for gateway, user, product, cart, order, payment, inventory, and notification services.
- The repository has unit/MockMvc-style tests and reports for ten test classes totaling 51 passing tests in the checked-in Surefire reports. Re-run the build before quoting this number in an interview.
- Testcontainers classes exist for some repository/integration coverage, but the Docker-backed end-to-end suite has not been established as passing in this session.
- Docker Compose configuration validates, but a full multi-container runtime smoke test has not been completed here.
- The frontend source and Dockerfile/nginx configuration exist, but a current frontend production build was not verified in this session.
- Prometheus/Grafana configuration exists, but a service POM dependency for `micrometer-registry-prometheus` was not found during inspection. Verify and add the correct dependency before claiming `/actuator/prometheus` is available.
- Kubernetes and Jenkins manifests are deployment configuration examples, not evidence that a live cluster or Jenkins instance has deployed the system.
- The payment service uses a simulated gateway, not a real payment processor. It does not process or store real card data.
- The compose file contains development-oriented defaults and a static Kafka cluster ID. It is useful for local development, not a secure production deployment.

## 2. Architecture and system design

### Q1. Explain ShopSphere in one minute.

**A.** ShopSphere is a modular e-commerce system. A React client calls a Spring Cloud Gateway, which validates JWTs, applies rate limits, and routes requests to domain services. Product, cart, order, payment, inventory, and user services own their data and expose focused APIs. Kafka carries asynchronous domain events for checkout, notifications, and compensation. Redis supports caching and distributed rate limiting. The design separates synchronous request-time work from asynchronous saga steps so services can be deployed and scaled independently.

### Q2. Why use microservices here instead of a modular monolith?

**A.** Microservices are appropriate when teams need independent deployment, different scaling profiles, and fault isolation. Product browsing can scale separately from payment processing, for example. The cost is distributed-data consistency, network failures, operational complexity, and harder end-to-end debugging. A modular monolith would be simpler for a small team; I chose services here to demonstrate and practice those trade-offs. The deciding question is whether the operational and consistency costs are justified by actual team and scaling needs—not whether microservices are fashionable.

### Q3. What is the difference between synchronous and asynchronous communication in this project?

**A.** Synchronous REST is used when the caller needs a result before it can respond, such as cart validation or order creation. Kafka is used for asynchronous facts and saga transitions, such as inventory reservation and payment completion. REST gives a direct request/response contract but couples availability and latency. Kafka decouples producer and consumer lifecycles but introduces eventual consistency, retries, ordering questions, and duplicate delivery. I use both deliberately: synchronous where the business operation needs an immediate answer, asynchronous where the workflow can progress independently.

### Q4. Draw the checkout flow and explain every hop.

**A.** The client posts an order request to the gateway. The gateway validates the JWT and forwards to order-service. Order-service obtains the customer’s cart from cart-service, snapshots order items and totals, saves the order, and writes `order.created` to its outbox in the same database transaction. The outbox relay publishes the event. Inventory-service consumes it, locks and validates stock, reserves quantities, and writes `inventory.reserved` or `inventory.failed` to its outbox. Payment-service consumes `inventory.reserved`, checks its payment idempotency key, calls the simulated gateway, records ledger entries, and publishes `payment.completed` or `payment.failed`. Order-service consumes the result and transitions the order to confirmed or cancelled. Order cancellation publishes compensation events; inventory releases reservations and payment can refund a refundable payment. Notification-service independently consumes relevant events.

### Q5. Why choreography instead of saga orchestration?

**A.** Choreography lets each service own its local state transitions and publish facts; there is no central coordinator that must be available for every step. That suits this mostly linear checkout flow and demonstrates loose coupling. The trade-off is that the workflow is distributed across consumers and can be harder to trace or evolve. Orchestration would centralize decision-making and may be easier to reason about for complex branching workflows, but it introduces a coordinator bottleneck and a new failure domain. I would choose orchestration if the business process had many conditional paths, human approvals, or compensation rules that are difficult to distribute safely.

### Q6. What is the transactional outbox, and what problem does it solve?

**A.** It solves the dual-write problem: a service cannot atomically commit a database transaction and publish a Kafka message. If it saves the order then publishes separately, a crash between those operations can either lose the event or publish an event for rolled-back data. The service writes the domain change and an outbox row in one database transaction. A relay later reads unpublished rows, publishes them, and marks them published. This makes the event part of the local transaction’s durable state. It is not exactly-once end to end: consumers still need idempotency, and relay publication/marking needs careful retry handling.

### Q7. What happens if the outbox relay crashes after sending to Kafka but before marking the event published?

**A.** The event may be published again on retry. Kafka consumers therefore must be idempotent. In this project, services record processed event IDs and payment uses an order-scoped idempotency key. The outbox provides at-least-once delivery semantics, not exactly-once business effects. A more advanced design could use Kafka transactions or a more precise inbox/outbox protocol, but each has its own operational and consistency trade-offs. The interview answer should emphasize duplicate handling and observability rather than claiming exactly-once delivery.

### Q8. Why database-per-service?

**A.** Each service owns its schema and can evolve, deploy, back up, and scale independently. It avoids hidden coupling through shared tables and makes ownership clear. The cost is that cross-service queries and joins are unavailable; data must be fetched through APIs or synchronized through events. I use logical identifiers across service boundaries, not foreign keys. This is a deliberate consistency boundary: local transactions stay inside one service, while cross-service workflows use sagas and compensation.

### Q9. What consistency model does ShopSphere use?

**A.** It uses local ACID transactions inside each service and eventual consistency across services. The checkout saga coordinates state changes through events. Payment may complete before notification is sent; inventory may be reserved before payment succeeds. The system handles failures with retries, idempotent consumers, compensation events, and reconciliation/observability. It does not pretend that the entire distributed checkout is one atomic transaction. For stronger guarantees, I would define invariants, use idempotency keys, reconcile uncertain external calls, and add operational alerts for stuck saga states.

### Q10. How would you evolve this into an orchestrated saga?

**A.** I would introduce a workflow coordinator that owns an explicit state machine and saga instance record. It would send commands such as reserve inventory and charge payment, receive results, and decide the next transition or compensation. The services would still own their local data and enforce local invariants. I would persist saga state and command IDs, make commands idempotent, add timeouts for missing responses, and design compensation for every completed step. I would only do this if choreography became difficult to reason about or required too many implicit interactions.

### Q11. When would you choose a modular monolith instead?

**A.** For a small team, early product, or workload that does not need independent scaling, a modular monolith is often the better starting point. It preserves clear module boundaries while avoiding distributed transactions, network calls, and many deployment pipelines. I would extract services when there is a concrete need: independent release cadence, distinct scaling, organizational ownership, fault isolation, or technology constraints. Microservices are an organizational and operational choice as much as a technical one.

### Q12. How do you decide synchronous versus asynchronous integration?

**A.** Use synchronous calls when the caller cannot proceed without a current result and the operation is short and latency-sensitive, such as validating a product before adding it to a cart. Use events when the producer should not wait for all downstream work, when consumers need independent scaling, or when the operation represents a durable fact. Add timeouts, retries only where safe, circuit breakers, and clear error semantics. Never hide a long-running or unreliable workflow behind a synchronous HTTP call without a timeout and fallback strategy.

### Q13. What are the main failure modes of a distributed checkout?

**A.** Duplicate events, consumer crashes after processing but before offset commit, outbox relay failure, network partitions, stale inventory, payment gateway timeouts where the charge may or may not have occurred, duplicate saga transitions, and stuck orders when a compensation event is lost. The design addresses many with idempotency, outbox, processed-event records, retries, DLQs, reservation leases, payment idempotency keys, and state checks. Remaining gaps should be handled with reconciliation jobs, alerting, and operational runbooks.

### Q14. How do you handle a service going down?

**A.** First distinguish read paths from workflow paths. A product catalog outage should not necessarily stop order history reads, but checkout may need to fail safely. Use timeouts so callers do not hold resources indefinitely. Use circuit breakers to stop repeated calls to a failing dependency and return a controlled degraded response. For asynchronous workflows, Kafka buffering can allow temporary consumer downtime, but lag and retry/DLQ growth need alerts. Kubernetes readiness probes should remove unhealthy pods from service endpoints, while liveness probes should restart only genuinely stuck processes.

### Q15. How do you handle partial failure in a multi-service workflow?

**A.** Define which steps have completed, which are pending, and what compensation each completed step requires. Persist workflow state and use idempotent commands. For example, if payment fails after inventory reservation, publish or trigger inventory release. If order cancellation arrives after payment capture, refund according to payment policy. Never assume a compensating action succeeded; record its result and retry or alert. A saga is not a transaction; it is a sequence of local transactions coordinated by events or a workflow engine.

### Q16. What is the difference between eventual and strong consistency, and where does ShopSphere fall?

**A.** Strong consistency means a read observes all previously committed writes relevant to the operation, often through a single transaction or consensus. Eventual consistency means replicas or services may temporarily disagree but converge. ShopSphere uses ACID transactions within a service and eventual consistency across services. For example, an order can be confirmed in order-service before the notification-service has processed the confirmation event. The product catalog cache may briefly show stale data. The design accepts this for availability and scalability, while using constraints and idempotency to protect critical invariants.

### Q17. How would you scale the system?

**A.** Scale stateless services horizontally behind the gateway and use Kubernetes HPA where appropriate. Use connection pools and database capacity planning for stateful services. Partition Kafka topics by aggregate ID so related events for one order remain ordered. Monitor consumer lag and scale consumers carefully because partition count limits parallelism. Cache read-heavy product data in Redis with explicit invalidation. Keep payment and inventory throughput within database lock and transaction limits. Avoid scaling a stateful saga coordinator without durable state and leader-safe coordination.

### Q18. What would you monitor first in production?

**A.** I would monitor service-level request rate, latency percentiles, error rate, saturation, and dependency health. For this system, I would specifically watch Kafka consumer lag, DLQ depth, outbox backlog and age, database pool wait time, inventory reservation expiry, payment gateway timeout/unknown-result rate, order saga age by state, cache hit rate, and gateway rate-limit rejections. Metrics should be paired with structured logs containing correlation and event IDs. Alerts should identify user impact and point to the next diagnostic step.

### Q19. How do you debug an order stuck in PENDING?

**A.** Start with the order ID and order number, then inspect its saga state and timestamps. Check whether `order.created` exists in the order outbox and whether it was published. Check inventory consumer lag and processed-event records, then inventory reservation/outbox state. Check payment idempotency key, payment status, and payment outbox. Look for exceptions in service logs and DLQ messages. Check whether inventory failed, payment failed, or an event was never produced. Do not manually change multiple databases first; identify the last durable fact and repair through an idempotent operation or compensating event.

### Q20. What is the biggest architectural risk in this project?

**A.** The biggest risk is distributed consistency and operability: a checkout spans multiple databases and brokers, and the current implementation is a strong portfolio design rather than a fully proven production system. Specific risks include event ordering assumptions, external payment uncertainty, stale documentation or manifests, missing end-to-end verification, and the need for reconciliation. I would address these with integration chaos tests, contract tests, distributed tracing, reconciliation jobs, production-like load tests, and a clear incident runbook before calling it production-ready.

## 3. Service-by-service technical Q&A

### API gateway

#### Q21. Why terminate JWT validation at the gateway?

**A.** The gateway is the single public trust boundary. It can reject unauthenticated traffic before it reaches domain services and inject trusted identity headers. This avoids duplicating JWT parsing in every service and keeps services simpler. The trade-off is that internal services must not be publicly exposed, and the gateway becomes a critical component. In a zero-trust network, services may still validate tokens or use mTLS and service identity; gateway validation alone is not sufficient if internal endpoints are reachable.

#### Q22. How does the gateway prevent clients from forging `X-User-Id` or `X-User-Roles`?

**A.** The global filter removes those headers from the incoming request before deciding whether to authenticate or route. For protected routes, it validates the JWT and then adds identity headers derived from verified claims. For public routes, it forwards the request without identity headers. This is essential because downstream services trust those headers. The implementation should be tested with spoofed headers on both public and protected paths, and network policy should prevent clients from bypassing the gateway.

#### Q23. Why is the gateway reactive/WebFlux while domain services use servlet Spring MVC?

**A.** Gateway traffic is mostly proxying and I/O-bound, so a non-blocking stack can handle many connections without allocating a thread per request. Domain services use familiar blocking Spring MVC with JPA transactions, which is easier to reason about for database-heavy workflows. The gateway must preserve backpressure and timeouts, and reactive code requires care with blocking calls. I would not mix blocking JPA calls into gateway filters.

#### Q24. What does the gateway rate limiter actually limit?

**A.** The YAML configures a Redis-backed `RequestRateLimiter` as a default filter with a replenish rate and burst capacity. The key resolver uses the remote IP. This limits requests per gateway instance key in Redis, but the exact effective policy depends on Spring Cloud Gateway configuration and Redis behavior. For production, I would define route-specific limits, authenticated-user limits, trusted-proxy handling, 429 responses, and tests. Never trust `X-Forwarded-For` unless the proxy chain is controlled.

#### Q25. What is wrong with using a symmetric JWT secret in both user-service and gateway?

**A.** It is simple for a demo but expands the blast radius: any service with the secret can mint tokens. A stronger design uses asymmetric signing: user-service signs with a private key and verifiers use a public key or JWKS. Key rotation, audience, issuer, token type, clock skew, and algorithm allowlisting also need explicit policy. The current HS256 implementation pins the algorithm, validates expiry, and fails fast on a short secret, which is better than an unpinned or hard-coded secret, but still requires secure secret management.

#### Q26. How would you secure internal service-to-service calls?

**A.** Do not expose internal ports publicly. Use network policies or a private service mesh, mTLS for transport identity, short-lived workload credentials, and authorization checks for service actions. For user-originated requests, propagate a verified user identity and roles through trusted headers only after stripping client copies. For event consumers, authenticate the broker or use a private Kafka network and validate event schemas. Defense in depth means gateway auth plus internal network controls, not gateway auth alone.

### User service and authentication

#### Q27. Why hash passwords with BCrypt?

**A.** BCrypt is an adaptive password hash designed to be slow and salted, making offline brute-force attacks expensive. It includes a work factor that can be increased as hardware improves. I would never store plaintext or reversible encryption. Argon2id is a modern alternative with strong resistance to GPU attacks; bcrypt remains widely supported. The password policy and hashing are separate concerns: policy rejects weak inputs, while hashing protects stored credentials.

#### Q28. Why use JWT access tokens plus opaque refresh tokens?

**A.** Access tokens allow stateless authorization on each request without a session lookup. They are short-lived to limit exposure. Refresh tokens are opaque random credentials stored as hashes and rotated on use, allowing revocation without making every API request stateful. This balances scalability and security. JWTs are bearer tokens, so TLS, short expiry, audience/issuer checks, and secure storage matter. Refresh token reuse detection is an additional safeguard against token theft.

#### Q29. What happens when a revoked refresh token is reused?

**A.** The service treats reuse as a possible theft signal and revokes all refresh tokens for that user, forcing reauthentication. This follows a token-family defense: once an old token appears after rotation, the family cannot be trusted. Legitimate clients must handle 401 by clearing tokens and sending the user through login. Race conditions and retries must be considered so a network retry does not accidentally look like token theft; use a single-flight refresh flow and understand the server’s rotation semantics.

#### Q30. Why store only a hash of the refresh token?

**A.** If the database is compromised, an attacker should not receive usable refresh credentials. SHA-256 is appropriate for a high-entropy random token because brute force is impractical; password hashing algorithms like BCrypt are intended for low-entropy human passwords and are unnecessary here. The raw token is returned once to the client and never persisted. Lookup uses the deterministic hash, while token rotation and revocation update the stored state.

#### Q31. Why are roles eager-loaded but addresses lazy-loaded?

**A.** Roles are needed to build authorities during authentication, so eager loading avoids repeated queries on the authentication path. Addresses are only needed for profile operations, so lazy loading avoids unnecessary data transfer and query cost on login or other requests. Fetch strategy should follow access patterns, but eager loading can still cause cartesian-product issues with multiple collections. In a larger model, separate queries or entity graphs may be safer than blindly eager-loading collections.

#### Q32. What is IDOR and how does this project address it?

**A.** Insecure Direct Object Reference occurs when a user changes an ID in a URL or request to access another user’s resource. The order and profile operations derive the customer identity from the authenticated principal or trusted gateway header and verify ownership before returning or modifying data. The client must not be allowed to choose another customer ID. I would add tests for cross-user access and ensure internal service headers cannot be spoofed by bypassing the gateway.

#### Q33. What are the trade-offs of JWT?

**A.** JWTs are compact and stateless, but revocation before expiry is difficult unless you use a denylist, short TTL, or introspection. Claims can become stale, token size grows with roles, and key management is critical. A leaked access token can be used until expiry. Refresh rotation limits long-lived exposure. For highly sensitive operations, reauthentication or step-up authorization may be required. I would also validate issuer, audience, algorithm, and token type rather than trusting any well-formed JWT.

### Product service

#### Q34. Why return DTOs instead of JPA entities?

**A.** Entities can expose internal fields, lazy associations, persistence annotations, and implementation details. DTOs define a stable API contract and prevent accidental serialization of sensitive or internal data such as cost price. Manual mapping makes transformations explicit; MapStruct can reduce boilerplate. The boundary is especially important when an entity has lazy collections, version fields, soft-delete state, or database-specific types. I would keep entity-to-DTO mapping at the service/controller boundary and avoid returning entities directly.

#### Q35. How do you prevent N+1 queries?

**A.** I inspect repository fetch behavior and use entity graphs, join fetch, or batch fetching for associations needed by the response. The exact implementation should be checked against the current repository. Avoid accessing lazy collections inside loops after the transaction or persistence context has closed. For lists, paginate at the database rather than loading everything and filtering in memory. Measure with query logs or metrics; do not add fetch joins blindly because they can create cartesian products or fetch unnecessary data.

#### Q36. Why soft-delete products?

**A.** Orders and historical records may reference products. A hard delete can break historical display, audit reconstruction, or foreign-key integrity. Soft deletion preserves the record while excluding it from normal purchase flows. The trade-off is that every read must consistently filter deleted records and indexes must support that predicate. I would use a status or deleted timestamp consistently, enforce it in repository queries, and document retention and purge policies.

#### Q37. Why whitelist sort parameters?

**A.** Allowing arbitrary sort fields can expose internal columns or create unsafe query construction. A whitelist maps user-friendly values such as `price_asc` to known `Sort` objects. This makes the API predictable and prevents property-path injection or accidental expensive sorts. I would also cap page size, validate filter ranges, and use database indexes appropriate for common sort/filter combinations.

#### Q38. How would you implement product search at scale?

**A.** Start with indexed database queries for simple filters and pagination. If search needs relevance ranking, typo tolerance, faceting, or high read volume, introduce Elasticsearch or OpenSearch and keep the relational database as the source of truth. Use events or an outbox to update the search index, handle indexing failures with retries, and reconcile drift. Cache only appropriate read models with explicit invalidation. Do not make the search index the transactional authority for inventory or order state.

#### Q39. What should happen when a product price changes after it is in a cart?

**A.** Cart snapshots can provide a fast display estimate, but checkout must revalidate current product state and price from the authoritative source before creating an order. If the price changed, the user should see the updated total and confirm again. The order should snapshot the accepted price for historical accuracy. Inventory and product status must also be rechecked because a product can be discontinued or stock can change after the cart was created.

### Cart service

#### Q40. Why use RestClient instead of WebClient for product lookups?

**A.** The cart operation is a conventional request-response flow and needs the product result before it can finish. A blocking client is simpler to reason about and fits the servlet stack. WebClient is useful for non-blocking I/O, streaming, or reactive pipelines, but wrapping it around a blocking database transaction would not automatically make the system non-blocking. I would choose based on the caller’s execution model and the need for composition, not on fashion.

#### Q41. Why snapshot product name, SKU, and price in cart items?

**A.** It lets the cart render without calling product-service for every line and preserves the display context at the time the item was added. It is not the final pricing authority. At checkout, order-service must revalidate and compute totals server-side. If product data changes, the UI may show a stale snapshot until refreshed; the order must not silently accept stale prices. The snapshot is a UX optimization, while the order is a business record.

#### Q42. How is one-cart-per-customer enforced?

**A.** A unique constraint on customer ID in the cart table is the durable guarantee. The service uses get-or-create logic so concurrent requests converge on the same cart. Application-level checks alone are insufficient under concurrency. I would test concurrent cart creation and rely on the database constraint, handling unique-key conflicts with a retry or read-after-conflict strategy.

#### Q43. What happens when a customer changes quantity concurrently?

**A.** The cart update must be transactional and use an optimistic version or appropriate locking strategy. Two requests that read quantity 2 and write 3 can lose an update if not protected. The cart entity has versioning in the current design, but the exact retry behavior should be verified. For user-facing quantity changes, retrying an optimistic-lock conflict is usually acceptable; for stock, stronger serialization is needed because overselling has a higher business cost.

#### Q44. Why does order-service fetch the cart instead of trusting the browser to submit items?

**A.** A browser can modify request bodies, so client-supplied prices or quantities cannot be trusted. Order-service calls cart-service using the authenticated customer identity and builds order items from the server-side cart. This reduces tampering and centralizes order construction. It still depends on cart-service availability and requires timeout handling. A more robust checkout may use a dedicated checkout command with server-side price validation and inventory reservation semantics.

### Order service

#### Q45. Why is order-service the saga participant rather than a central orchestrator?

**A.** In the implemented choreography, order-service creates the order and reacts to downstream events; it does not synchronously command inventory and payment. It owns the customer-facing order state and publishes facts. This keeps the initial implementation decentralized. The service still has important responsibilities: authoritative totals, state transitions, ownership checks, and compensation events. If the workflow becomes complex, a separate orchestrator could own the state machine while services retain local transactions.

#### Q46. How are order totals calculated?

**A.** Order-service computes subtotal from cart lines, applies tax and shipping rules, and stores the total using decimal arithmetic. The current code uses an 8% tax rate and a shipping rule with a free-shipping threshold. These rules should be externalized into configuration or a pricing policy before production because tax and shipping are jurisdiction- and business-dependent. Never calculate final money with floating-point types; use `BigDecimal` and explicit rounding rules.

#### Q47. Why snapshot order items?

**A.** An order is a historical record of what the customer purchased at a particular price and quantity. Product names, SKUs, and prices may change later. The order item preserves the accepted commercial terms and supports invoices, audits, returns, and customer history. The product ID remains a logical reference for traceability, but the order does not depend on the current product row to render historical details.

#### Q48. What invariants does the order state machine enforce?

**A.** The entity methods reject illegal transitions, such as confirming an order that is not pending or shipping an order that is not confirmed. Cancellation is allowed only before shipment in the current model. The exact allowed transitions should be reviewed against business policy, especially refunds, returns, and partial cancellation. Keeping transition rules in the entity or a dedicated state machine prevents controllers and event handlers from accidentally bypassing lifecycle constraints.

#### Q49. What happens if two payment events arrive for the same order?

**A.** The consumer should deduplicate by event ID and the service should check current order state before applying a transition. A payment completion after cancellation must not blindly confirm the order. The current handlers check for pending state in the inspected flow, but concurrent event processing and ordering need integration tests. Payment itself uses an order-scoped idempotency key. The correct behavior depends on business rules: a late success after cancellation may require reconciliation or refund rather than re-confirmation.

#### Q50. Why does cancellation publish another event instead of directly calling inventory and payment?

**A.** Event-driven compensation preserves service autonomy and avoids order-service needing to know every downstream implementation. Inventory and payment can react to `order.cancelled` according to their local state. This also creates an audit trail. The trade-off is eventual consistency and the need for idempotent compensation. Direct synchronous compensation could reduce latency but increase coupling and partial-failure complexity. The choice should be based on workflow ownership and consistency requirements.

### Inventory service

#### Q51. Why use pessimistic locking for stock reservation?

**A.** Inventory has a hard invariant: available stock must not be oversold. Pessimistic row locks serialize reservation attempts for the same stock record, making validation and mutation atomic within the transaction. This can reduce concurrency and increase contention, so it should be scoped to the stock rows and transaction. Optimistic locking may be suitable for lower-contention entities, but retry storms and repeated conflicts can be problematic for a hot inventory row. The correct choice depends on contention, transaction duration, and business tolerance for overselling.

#### Q52. Explain the two-phase reservation algorithm.

**A.** The service first validates all requested lines and gathers the stock rows it needs, then applies all reservations only if every line has enough stock. This avoids partial reservations when one item is unavailable. Stock rows are locked during the transaction to prevent concurrent changes. If validation fails, the service publishes a failure event and does not mutate stock. If validation succeeds, it increments reserved quantities, creates reservation records with expiry, and writes the success event to the outbox. The database transaction makes local state and outbox insertion atomic.

#### Q53. Why do reservations expire?

**A.** A reservation is a temporary hold during checkout, not a permanent allocation. If payment never completes or the customer abandons checkout, the held stock must return to availability. A lease with an expiry timestamp lets a sweeper release stale reservations. The sweeper must be idempotent and safe under concurrent execution. The TTL should match the expected payment window and be monitored; too short can cancel valid checkouts, too long can unnecessarily reduce sellable inventory.

#### Q54. What happens if inventory receives the same `order.created` event twice?

**A.** The consumer records processed event IDs and the reservation logic checks for an existing active reservation by order and product. Together these provide defense in depth against duplicate delivery. The exact uniqueness constraints and transaction boundaries should be verified. Idempotency is essential because Kafka is at-least-once. A duplicate must not reserve stock twice or publish duplicate success events. The same principle applies to release and expiry operations.

#### Q55. How do you prevent overselling under high concurrency?

**A.** Serialize access to the stock invariant with database locks or an atomic conditional update, validate available quantity inside the same transaction, and ensure all-or-nothing behavior for multi-item orders. Add database constraints such as nonnegative available stock and reserved quantity not exceeding on-hand quantity. Test concurrent reservations for the same SKU. Monitor lock waits and transaction duration because a correct but slow lock can become an availability problem. For extreme scale, consider inventory partitioning, per-SKU queues, or a dedicated allocation service with stronger capacity planning.

#### Q56. What is the difference between stock on hand, reserved, and available?

**A.** On-hand is physical or recorded inventory. Reserved is quantity temporarily allocated to orders. Available is the quantity that can still be sold, typically on-hand minus reserved and possibly damaged or quarantined stock. The database uses a generated available column in the current schema, but the business definition should be explicit. Do not confuse available with shipped or fulfilled stock. Reporting should show all three values and the reservation state rather than one ambiguous number.

### Payment service

#### Q57. Why use a payment gateway port and adapter?

**A.** The domain should not depend directly on a particular payment provider’s API. A `PaymentGateway` interface lets the simulated implementation be replaced by Stripe, Adyen, or another provider through configuration and an adapter. The adapter translates domain operations into provider requests and provider responses into domain results. This follows dependency inversion and makes provider-specific errors, retries, webhooks, and reconciliation easier to isolate. The current simulated gateway is for development and tests, not real money movement.

#### Q58. Why is payment idempotency critical?

**A.** A customer must not be charged twice because Kafka redelivered an event, a client retried, or a saga step ran again. The service uses an order-scoped idempotency key and a unique database constraint. Before charging, it checks for an existing payment and returns it on replay. The payment provider should also support idempotency for the same operation. Local dedupe alone cannot protect against crashes after the provider accepts a charge but before the local result is committed; reconciliation is needed for that uncertain window.

#### Q59. What happens if the payment gateway times out?

**A.** A timeout does not prove the charge failed; the provider may have accepted it. The system should record an unknown/pending state, use the provider’s idempotency key to query or retry safely, and reconcile before issuing another charge or refund. It should not blindly retry with a new key. The current simulated gateway does not reproduce all provider timeout semantics, so this is a production hardening discussion. Webhook confirmation and a reconciliation job are standard answers for real PSP integrations.

#### Q60. Why keep an append-only transaction ledger?

**A.** Payments require an auditable history of authorization, capture, refund, and failure attempts. Append-only records preserve corrections as new entries instead of overwriting history. This supports reconciliation, dispute handling, accounting, and debugging. The ledger should include provider references, idempotency keys, amounts, currency, status, timestamps, and correlation IDs. Access should be restricted and retention policies defined. Corrections should be compensating entries rather than destructive updates.

#### Q61. Why does retry use a new idempotency key?

**A.** A retry is a new charge attempt after a known failed payment, so it must be distinguishable from the original attempt. Reusing the original key could cause the provider to return the prior failure or prevent a legitimate retry. The implementation appends a retry identifier and records each attempt in the ledger. However, retry policy must be deliberate: automatic retries should only occur when the previous outcome is known to be safe to retry. Unknown timeouts require reconciliation, not a new charge key.

#### Q62. How does auto-refund work?

**A.** When order cancellation is received, payment-service finds the payment for the order and refunds it only if the payment state is refundable, such as captured. It calls the gateway refund operation with a refund idempotency key, records a refund ledger entry, changes the payment state, and publishes a refund event through the outbox. The operation must be idempotent because cancellation events can be redelivered. A refund failure should be visible and retried or escalated; cancellation of the order should not be reported as fully compensated until refund state is known.

### Notification service

#### Q63. Why does notification-service have no database?

**A.** It is an event-driven side-effect service: it consumes facts and sends simulated messages. It does not own customer or order state. Dedupe state is kept in Redis with a TTL, which is sufficient for the notification use case and avoids adding a database. This is a deliberate boundary, not a claim that every service should be database-less. If notification delivery needs durable retry, templates, user preferences, or audit history, a persistent store or a dedicated messaging platform may be appropriate.

#### Q64. How does Redis dedupe notifications?

**A.** The service uses an atomic Redis `SETNX`-style operation keyed by event ID with a TTL. The first delivery marks the event as seen; later duplicates are skipped. This is appropriate for ephemeral notification side effects and avoids a relational table. It is not a general exactly-once mechanism: Redis failure, TTL expiry, or replay outside the retention window can change behavior. For durable exactly-once-like effects, use a persistent inbox and transactional outbox with explicit delivery state.

#### Q65. How does the dead-letter topic work?

**A.** The Kafka listener error handler retries transient failures with a backoff. After retries are exhausted, the recoverer publishes the failed record to a dead-letter topic with diagnostic headers. This prevents one poison message from blocking a partition indefinitely and preserves the payload for inspection or replay. A DLT is not a trash can: it needs alerts, retention policy, replay tooling, and a process for fixing the root cause. Never silently drop failed messages.

#### Q66. Why use JSON strings rather than Java serialization for Kafka events?

**A.** JSON is language-neutral, easier to inspect, and avoids Java class coupling and serialization vulnerabilities. Consumers can ignore unknown fields, which supports additive schema evolution. The trade-off is that JSON does not enforce schemas as strongly as Avro or Protobuf. For stricter contracts, use a schema registry and compatible schema evolution. The current implementation should validate required fields and version envelopes rather than assuming every producer payload is correct.

## 4. Real-world project scenarios and incident questions

### Q67. A customer says they were charged but the order is cancelled. Walk me through your investigation.

**A.** I would identify the order, customer, payment ID, and payment idempotency key without exposing sensitive data. I would check the order state and saga events, payment status and ledger, and whether `order.cancelled` was published. I would determine whether the charge was captured before cancellation and whether refund was attempted. I would inspect Kafka consumer lag, DLQs, and outbox backlogs. If the payment was captured and refund failed, I would retry through an idempotent refund operation or escalate to the payment operator. I would avoid issuing a second charge or refund manually until the provider state is reconciled. The customer-facing answer should include the current state, expected next step, and escalation path.

### Q68. Inventory shows stock but checkout says insufficient stock. What could cause it?

**A.** Active reservations may be holding stock, the generated available quantity may subtract reservations, the reservation may not have expired yet, or a transaction may have updated on-hand without releasing the reservation. I would compare on-hand, reserved, available, active reservation rows, expiry timestamps, and recent order events. I would check for a stuck sweeper, duplicate reservations, or a failed compensation. I would not simply increase stock to make checkout pass; that can hide the invariant violation. The fix should release only reservations proven stale or repair the specific failed workflow.

### Q69. Kafka consumer lag is rising during a sale. What do you do?

**A.** First identify which consumer group and topic are lagging and whether the issue is consumer downtime, slow processing, partition skew, database lock contention, or downstream dependency latency. Check CPU, memory, GC, DB pool waits, and exception rates. Increase consumer replicas only up to the partition count and only if processing is stateless and idempotent. Scale partitions carefully because partition changes affect ordering and key distribution. Add alerts for lag growth and DLQ depth. Do not increase Kafka retention as a substitute for fixing consumers.

### Q70. A new product price is not appearing in the storefront. How do you debug it?

**A.** Verify the product update transaction committed and the correct database/tenant was used. Check product status, soft-delete state, category visibility, and whether the request is hitting a cached response. Inspect cache keys and TTLs, invalidation events, Redis availability, and gateway routing. Check whether the frontend is requesting the right product or category and whether pagination or filters exclude the item. If a search index is used, verify indexing lag and reconciliation. Reproduce with a direct service call to separate gateway, cache, service, and database issues.

### Q71. Users report random logouts. What are possible causes?

**A.** Access token expiry without successful refresh, refresh token rotation races, browser storage clearing, multiple tabs refreshing concurrently, clock skew, Redis/session dependency failure, or gateway stripping/forwarding headers incorrectly. I would inspect token expiry and token type, refresh reuse detection, browser network traces, and service logs. The frontend uses a shared refresh promise to reduce concurrent refresh races, but the end-to-end behavior still needs browser testing. A production design may use secure cookies or a centralized token service with explicit rotation semantics.

### Q72. How do you roll out a breaking API change?

**A.** Use API versioning and a compatibility window. Add the new contract alongside the old one, migrate clients, monitor adoption, then remove the old contract after a deprecation period. Use contract tests and consumer-driven contracts so producers and consumers agree. For Kafka events, add optional fields first and never remove or rename fields without a versioned migration. Use feature flags for risky behavior and canary deployments. A breaking change should be visible in CI before it reaches production.

### Q73. How do you perform a database migration safely?

**A.** Make migrations backward-compatible with the currently running application where possible: add nullable columns before backfilling, avoid long table locks, and deploy code that works with both old and new schema during rollout. Use Flyway with reviewed, immutable migrations and test them against realistic data volumes. For large tables, use online schema change tools or staged backfills. Have a rollback plan that may require forward-fix rather than dropping columns. Never edit an already applied migration; add a new version.

### Q74. A payment provider changes its API. How do you respond?

**A.** Keep provider-specific code inside the adapter and map the new API there. Add contract tests against sanitized provider fixtures, update error mapping and webhook verification, and run reconciliation against provider transaction references. Deploy the adapter independently if possible, use feature flags or dual-provider routing for migration, and monitor unknown payment states. Do not leak provider-specific fields into the domain model or Kafka events unless they are part of a stable contract. Real credentials and card data must never be logged.

### Q75. How would you handle a flash sale with a hot SKU?

**A.** I would load-test the checkout path, identify the hot partition and database row, and consider inventory sharding or a dedicated reservation queue. Use short transactions, strict timeouts, and backpressure. Cache product reads but never treat cache as inventory authority. Monitor lock wait time, Kafka lag, payment provider limits, and database connections. Rate-limit abusive traffic while preserving legitimate customers. Add a queue or admission control if demand exceeds downstream capacity, and communicate inventory limits honestly. The correct design depends on expected orders per second and provider limits.

### Q76. What would you do if Kafka is unavailable during checkout?

**A.** The order and outbox rows should remain durable if the local database transaction succeeded. The outbox relay should retry with backoff and alert on backlog. Consumers cannot process events until Kafka recovers, so orders may remain pending and inventory reservations may expire. The system should expose saga age and outbox lag, avoid declaring success prematurely, and provide a replay/reconciliation procedure. If Kafka is down before order creation, the synchronous request may still succeed because the outbox write is local; the user should see a pending state rather than a false confirmed state.

### Q77. How do you handle duplicate HTTP requests from a client?

**A.** Use idempotency keys for mutating operations, validate request fingerprints, and make retries safe. For cart additions, define whether the operation is additive or set-quantity. For order creation, a client idempotency key should map to one order and one saga. For payment, the order-scoped key prevents duplicate charges. Return the original result for a repeated key rather than executing again. Store idempotency records with expiry and conflict detection, and test concurrent retries.

### Q78. How do you ensure a customer only sees their own orders?

**A.** Authenticate at the gateway, inject trusted identity, and enforce ownership in order-service for every read and mutation. Use customer ID from the principal rather than a request parameter. Test direct object ID access, order-number guessing, pagination, and admin exceptions. If support agents need access, use a separate audited authorization path rather than weakening customer ownership checks. Internal service calls must also be protected from header spoofing.

### Q79. What data should never appear in logs?

**A.** Passwords, access/refresh tokens, JWTs, payment card numbers or CVV, provider secrets, database credentials, personally identifiable information beyond what is necessary, and full request bodies that may contain secrets. Use structured logs with allowlisted fields, redact sensitive headers and payloads, and restrict log access. Event IDs and order IDs are useful correlation fields; raw payment payloads generally are not. Add tests or log-review checks for accidental secret leakage.

### Q80. How do you test a distributed saga?

**A.** Use unit tests for state transitions and compensation rules, integration tests with real databases for transactions and constraints, and end-to-end tests with Kafka for event flow. Use Testcontainers for database realism and a Kafka test container or embedded broker where appropriate. Test duplicate events, consumer crashes, broker outages, payment timeouts, stock contention, and outbox relay retries. Use contract tests between services and chaos/failure injection for resilience. A passing unit test does not prove the distributed workflow is correct.

### Q81. How would you estimate whether the system can handle 10,000 orders per minute?

**A.** Break the flow into orders per second, items per order, Kafka messages per second, database transactions per second, payment provider calls, and notification fan-out. Measure latency and saturation at each hop, not just gateway throughput. Model peak-to-average ratio and failure retries. Identify the first bottleneck: database locks, connection pools, Kafka partitions, consumer CPU, or provider rate limits. Load test with realistic data and ramp gradually. Define SLOs for checkout latency and error rate before optimizing; add capacity only where measurements show a constraint.

### Q82. What would you change first if this were a real production launch?

**A.** I would close verification and operational gaps before adding features: run the full Compose stack, execute database migrations against clean and representative data, verify frontend build, add Prometheus registry dependencies and scrape tests, run end-to-end saga tests, validate K8s manifests against a cluster, and test rollback. I would replace development secrets and static Kafka configuration, add TLS and private networking, configure real secret management, define SLOs/alerts, and create incident runbooks. Then I would conduct security review and load testing. The current repository is a strong implementation base, not a launch approval.

## 5. Coding-agent and AI-assisted engineering best practices

These questions are about using an AI coding assistant responsibly on a real project. The goal is not to hide that AI was used; it is to demonstrate engineering judgment, verification, and accountability.

### Q83. How do you give an AI agent a task effectively?

**A.** I provide the business goal, constraints, relevant files, expected behavior, and acceptance criteria. I distinguish must-have behavior from optional improvements. I ask for a plan before large changes, require tests, and specify what should not change. Good prompts include context such as framework version, database ownership, API contract, and failure cases. I avoid asking for an entire system in one prompt because it produces broad, unreviewable output. I break work into reviewable increments and keep a record of decisions.

### Q84. What should you never let an AI agent do without review?

**A.** Never blindly accept changes to authentication, authorization, payment logic, database migrations, secrets handling, infrastructure permissions, or destructive operations. Review generated code for injection, broken access control, race conditions, transaction boundaries, data exposure, and compatibility. Run tests and inspect diffs. AI can produce plausible but incorrect code, especially around concurrency and distributed systems. Human ownership of security and correctness remains essential.

### Q85. How do you verify AI-generated code?

**A.** I read the diff, trace the call path, run the narrowest relevant tests, then broader tests and static analysis. I check edge cases, error handling, transaction boundaries, API compatibility, and whether comments match behavior. For database changes, I test migrations and rollback implications. For frontend changes, I build and exercise user flows. For infrastructure, I validate configuration and run a dry-run or local cluster where possible. I treat generated tests as candidates: they must fail for the right reason and assert meaningful behavior.

### Q86. What are common AI coding failure modes?

**A.** Hallucinated APIs or dependencies, outdated framework patterns, missing imports, plausible but incorrect business logic, over-broad refactors, tests that mirror implementation mistakes, comments that claim unsupported guarantees, and silent changes to unrelated files. In distributed systems, AI may overlook idempotency, transaction boundaries, retries, and partial failure. Mitigate with pinned dependencies, repository conventions, explicit requirements, small diffs, test-first workflows, and independent review.

### Q87. How do you use AI for debugging?

**A.** I first reproduce the issue and gather logs, stack traces, and a minimal failing test. I ask the agent to hypothesize causes and propose diagnostics, not just patch symptoms. I compare hypotheses against the code path and runtime evidence. I prefer adding a regression test before fixing the bug. I avoid pasting secrets or production data into prompts. If the agent cannot reproduce the issue, I keep investigating rather than accepting a speculative fix.

### Q88. How do you use AI for system design?

**A.** I ask it to enumerate options, trade-offs, failure modes, and operational costs. I require diagrams or written invariants, then challenge assumptions. AI is useful for brainstorming alternatives such as choreography versus orchestration, cache invalidation strategies, or migration paths. It is not a source of truth for production constraints. I validate recommendations against the actual repository, team size, traffic, compliance requirements, and existing technology stack.

### Q89. How do you keep AI-generated documentation accurate?

**A.** Documentation must be generated from verified implementation details and reviewed against code. I avoid copying aspirational descriptions as if they are current behavior. I label planned work, known gaps, and assumptions explicitly. I include commands that I have run and test results with their date/scope. When code changes, I update docs in the same change. A document that says “production-ready” without evidence is worse than no document.

### Q90. What is a safe workflow for a large AI-assisted change?

**A.** Start with a written plan and identify affected services and contracts. Create a branch and make small commits. Ask for one component or behavior at a time. Add or update tests before or alongside implementation. Run focused tests, then module tests, then integration tests. Review the complete diff for unintended changes. Update API/event/schema documentation. Deploy to staging, run smoke tests, and monitor. Keep rollback instructions ready. Never combine unrelated refactors with a risky feature in one AI-generated patch.

### Q91. How do you handle secrets when using AI tools?

**A.** Never paste passwords, API keys, JWT secrets, database URLs with credentials, production tokens, or sensitive customer data into a prompt. Use placeholders and environment variable names. Check generated files for accidental secrets before committing. Use `.env.example` with blank values and a real `.env` excluded by `.gitignore`. If a secret is exposed, rotate it immediately and investigate history. AI tool data handling and retention policies should be reviewed before sending proprietary code or data.

### Q92. How do you evaluate whether AI improved your productivity?

**A.** I measure accepted, verified changes rather than lines generated. Track review time, defect rate, test coverage, rollback frequency, and time to production. Compare tasks with and without assistance while accounting for task difficulty. AI may accelerate boilerplate and exploration but add risk in complex reasoning. The best outcome is faster delivery with the same or better quality, not merely more code. If review and debugging costs exceed the time saved, adjust the workflow.

## 6. Practical coding challenges and model answers

### Q93. Design an idempotent `POST /orders` endpoint.

**A.** Accept a client idempotency key, authenticate the customer, and look up an existing order by customer and key before creating a new one. In one transaction, create the order, items, totals, and outbox event. Use a unique constraint on the idempotency key to handle races. Return the original order for a duplicate request. If the request payload differs for the same key, return a conflict. Do not rely only on a Redis lock or application check. Add tests for sequential and concurrent retries.

### Q94. Implement a safe inventory reservation for multiple SKUs.

**A.** Lock stock rows in a deterministic order to reduce deadlocks, validate every requested quantity, and only then update all reservations. Use a transaction and database constraints. Return per-line failures without partial reservation. Make the operation idempotent by order and product. Add reservation expiry and a sweeper. Test concurrent purchases of the last unit, duplicate events, insufficient stock on one line, and crash recovery. Avoid holding locks longer than necessary and monitor lock wait time.

### Q95. Design refresh token rotation.

**A.** Generate a high-entropy opaque token, store only its hash, set an expiry, and return the raw token once. On refresh, atomically validate and revoke the old token, then issue a new access/refresh pair. Detect reuse of a revoked token and revoke the token family. Handle concurrent refreshes with a single-flight client strategy and clear server-side rules for race outcomes. Use TLS, secure cookie or safe browser storage, rotation, logout revocation, and audit logging. Never put refresh tokens in local storage without acknowledging XSS risk.

### Q96. Implement a Kafka consumer that must process each event once logically.

**A.** Kafka provides at-least-once delivery, so implement an inbox/processed-event record keyed by event ID. In the same transaction as the business effect, check and insert the event ID using a unique constraint. Commit the Kafka offset only after the transaction succeeds. On duplicate delivery, return without repeating the effect. Handle poison messages with bounded retries and a DLQ. Test duplicate delivery, failure before commit, failure after commit before offset acknowledgment, and concurrent partitions. Do not claim Kafka itself provides exactly-once business processing.

### Q97. Design a circuit breaker around an HTTP client.

**A.** Define failure criteria such as timeouts and 5xx responses, a failure threshold, a cooldown period, and a half-open probe. When open, fail fast with a controlled fallback instead of waiting for every request to time out. Emit metrics for state transitions and fallback usage. Choose fallback behavior based on the operation: a read may use stale cache, while checkout should return a clear unavailable response. Test open, half-open, closed, timeout, and dependency recovery scenarios. Do not retry indefinitely inside the breaker.

### Q98. How would you add distributed tracing?

**A.** Propagate a trace context through HTTP headers and Kafka headers. Use OpenTelemetry or Spring Micrometer Tracing with an OTLP exporter. Add span names for service operations, database queries, Kafka publish/consume, and external gateway calls. Set sampling appropriately and redact sensitive attributes. Correlate logs with trace and span IDs. Test that context survives gateway → service → Kafka → consumer. Use traces to diagnose latency and failed saga hops, but do not log secrets or full payloads.

### Q99. Write a migration for adding order cancellation reason.

**A.** First inspect the existing order schema and migration conventions. Add a nullable `cancel_reason` column with an appropriate length in a new Flyway version. Update the entity and DTO mapping, then add tests for cancellation with and without a reason. If existing rows need a default, decide whether null, empty string, or a historical sentinel is appropriate; avoid pretending old cancellations have a known reason. Deploy code compatible with the new nullable column. Do not edit an already applied migration. Validate against a database copy before production.

### Q100. Design a feature flag for a risky payment retry.

**A.** Store the flag in a configuration system with environment-specific values and audit history. Default it off. Gate the retry path explicitly and emit metrics for enabled/disabled attempts and outcomes. Use a kill switch that can disable retries without redeploying. Test both paths and ensure the flag cannot bypass idempotency or reconciliation. Monitor error and duplicate-charge indicators during rollout. Remove the flag after the behavior is stable, but keep operational controls for emergency disablement.

## 7. Interview answer framework

For any question, use this structure:

1. **Clarify the goal and constraints** — ask about scale, consistency, team, compliance, and latency if missing.
2. **State the recommendation** — give a direct answer before alternatives.
3. **Explain the mechanism** — describe components, data flow, and invariants.
4. **Discuss trade-offs** — cover cost, complexity, failure modes, and operational burden.
5. **Give a concrete example** — reference ShopSphere where relevant.
6. **Describe verification** — tests, metrics, rollout, rollback, and incident response.
7. **Mention what you would change at higher scale** — show growth path without overengineering the current answer.

### Strong versus weak answers

A weak answer says, “Use Kafka and microservices for scalability.” A stronger answer says, “I would use Kafka for durable asynchronous facts because consumers need independent availability, but I would keep checkout commands synchronous where the user needs an immediate result. I would add an outbox, idempotent consumers, DLQs, consumer-lag alerts, and saga reconciliation. I would choose orchestration instead if the workflow had complex branching or human approvals.”

A weak answer says, “Add a circuit breaker.” A stronger answer says, “Add a circuit breaker around the product lookup with a timeout, failure threshold, half-open probe, and a controlled fallback. I would not use the same fallback for checkout because a stale product price is unsafe. I would emit state-transition metrics and test dependency recovery.”

## 8. Questions to ask the interviewer

- What is the current production pain point: scale, reliability, deployment velocity, or team ownership?
- Which service or workflow has caused the most incidents recently?
- How are consistency and ordering requirements documented for cross-service workflows?
- What SLOs and error budgets are used, and how do they influence architecture decisions?
- How is schema/event compatibility enforced across independently deployed services?
- What does the on-call process look like for stuck sagas, DLQs, and payment reconciliation?
- How much operational ownership does the team expect from engineers?
- Are there compliance requirements that affect data retention, PCI scope, or regional deployment?

## 9. Final honesty checklist before the interview

- [ ] I can run the backend tests and explain what each test proves.
- [ ] I can start the Compose stack and walk through a checkout.
- [ ] I can point to the outbox, consumer idempotency, payment idempotency key, and inventory lock in source.
- [ ] I can explain what is simulated versus production-ready.
- [ ] I know which integration and end-to-end tests have actually passed.
- [ ] I can discuss the gateway header trust boundary and its bypass risks.
- [ ] I can explain the payment timeout/reconciliation problem honestly.
- [ ] I can describe monitoring gaps and the steps needed to close them.
- [ ] I can discuss AI-assisted development without overstating verification.
- [ ] I have practiced trade-off questions rather than memorizing framework syntax.

## 10. STAR-format behavioral questions

Behavioral questions are scored on Situation, Task, Action, Result. Below are
project-grounded examples using ShopSphere — adapt them to your own experience
but keep the structure: concrete context, your responsibility, specific actions,
and measurable or observable outcomes. Always end with what you learned.

### Configuration & environment management

**Q. Tell me about a time you managed configuration across multiple environments.**

*Situation:* ShopSphere has eight backend services plus a gateway and frontend,
each needing different values for database URLs, Kafka bootstrap servers, Redis
hosts, JWT secrets, and service URLs across local, Docker Compose, Kubernetes,
and CI.

*Task:* Establish a configuration strategy that works identically everywhere
without duplicating secrets or hardcoding environment-specific values.

*Action:* Centralized versions in the parent POM; used Spring Boot relaxed
binding with environment-variable defaults in each `application.yml`
(e.g., `${POSTGRES_HOST:localhost}`, `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`);
created `.env.example` with blank secrets; used Docker Compose env overrides;
created a Kubernetes ConfigMap for non-secret shared values and a Secret for
credentials, never committing real values.

*Result:* The same jar runs locally, in Compose, and on K8s with zero code
changes — only environment differs. Misconfiguration fails fast (e.g., JWT
secret <32 bytes throws at boot).

*Lesson:* Externalize config, fail fast on invalid values, keep secrets out of
git and out of logs.

**Q. Describe a time a configuration mistake caused an outage or bug.**

*Situation:* While wiring the gateway, the JWT secret env var name had to map to
`jwt.secret` via Spring relaxed binding — a subtle naming rule.

*Task:* Ensure the gateway and user-service share the same secret correctly.

*Action:* Verified binding rules, pinned HS256 explicitly after jjwt 0.12
auto-selected HS384 and threw `WeakKeyException` on a 256-bit secret; added a
constructor check requiring ≥32 bytes so misconfiguration fails at startup, not
per-request.

*Result:* The token contract is explicit, and bad config fails loudly during
deploy instead of silently rejecting all tokens in production.

*Lesson:* Verify framework binding behavior empirically; prefer fail-fast
validation over silent defaults for security-critical config.

### Optimizing token consumption (AI context budget)

**Q. Tell me about a time you had to work within a limited context/token budget on a large task.**

*Situation:* Building an eight-service platform generated large amounts of code
and docs; full file reads of every service would exhaust the session context.

*Task:* Complete remaining work without losing earlier architectural decisions.

*Action:* Used targeted searches (grep/glob) instead of reading whole files;
delegated broad exploration to a search subagent; kept canonical decisions in
`docs/` (interview-explanations, api-contracts, kafka-topics) so context could be
reconstructed from files rather than conversation; wrote compact files in
batches; summarized progress in tables instead of re-pasting code.

*Result:* All parts were delivered with 51 tests green and docs consistent —
the summary file at session end captured state so work could continue.

*Lesson:* Persist decisions in the repo, not the chat; search narrowly; write
summaries that let a fresh session resume.

**Q. How do you decide what context to give an AI agent vs. what to keep in the repo?**

*Situation:* Large codebase, limited prompt budget.

*Task:* Get accurate help without pasting the entire repository.

*Action:* Front-load only what the task needs: file paths, class names, the
relevant contract, and constraints. For cross-cutting work, point the agent at
docs (`docs/api-contracts.md`, `docs/kafka-topics.md`) instead of re-describing
the system in the prompt. Use subagents for open-ended exploration and only
pull their distilled findings back.

*Result:* Faster, cheaper iterations with fewer hallucinated references.

*Lesson:* Repo-as-memory + minimal prompt context beats giant prompts.

### Using an AI agent in application development

**Q. Tell me about a time AI-generated code introduced a bug — how did you catch it?**

*Situation:* Generated a `GlobalExceptionHandler`/OpenAPI config and a retry
endpoint; tests initially failed to compile and a BigDecimal assertion
mismatched scale (`0` vs `0.00`).

*Task:* Ship correct code, not plausible code.

*Action:* Ran `mvn test` after every generated module; fixed compile errors
(missing import, unclosed paren); corrected the test to compare with
`compareTo` instead of `assertEquals` on scale; re-ran until green. Also caught
that a controller mutation on a detached entity wouldn't persist and moved the
logic into a `@Transactional` service method.

*Result:* All payment-service tests passed (5/5) and the retry path is actually
transactional — the AI draft had a real persistence bug a reviewer might miss.

*Lesson:* Generated code is a draft; compile + test + trace-the-tx are the gate.

**Q. Describe how you used an AI agent on a distributed-systems feature.**

*Situation:* Implementing the checkout saga across order, inventory, and
payment services.

*Task:* Get a working choreography with outbox, idempotent consumers, and
compensation — the hardest correctness surface in the project.

*Action:* Built services incrementally (Part 6 inventory → Part 7 payment →
Part 8 order), each with its own unit tests before wiring the next consumer.
Kept event contracts explicit as Java records (`OrderEvents`,
`InventoryEvents`, `PaymentEvents`) so producer/consumer shapes couldn't drift
silently. Documented each part's "why" in `docs/interview-explanations.md`
immediately, which also served as the spec for the next part.

*Result:* The saga chain is fully wired in code and 24 saga-related tests pass;
each service's guarantees are documented and testable in isolation.

*Lesson:* Decompose AI work along service boundaries; tests + written contracts
are what make generated distributed code trustworthy.

**Q. Tell me about a time you had to push back on or correct AI output.**

*Situation:* The AI suggested `Sort.by(userInput)` for product sorting and a
shared DB approach early in design; also generated code that trusted
client-supplied cart items at checkout.

*Task:* Maintain security and correctness standards.

*Action:* Rejected arbitrary sort input (ORDER BY injection) in favor of a
whitelist; enforced server-side cart fetch so the client can't tamper with
prices; required gateway header-stripping before identity injection; kept
`processed_events` dedupe inside the same transaction as the business effect.

*Result:* The shipped code has explicit security properties (IDOR guards, header
spoofing tests, idempotency constraints) that survive review.

*Lesson:* Treat AI output as proposals; the engineer owns the invariants.

## 11. Suggested study order

1. Re-read the architecture and draw the saga from memory.
2. Trace `POST /orders` through order, inventory, payment, and notification code.
3. Explain each database constraint and why it exists.
4. Practice failure scenarios: duplicate event, Kafka outage, payment timeout, stock contention, stale cache.
5. Review gateway security and frontend token refresh.
6. Review Docker Compose, Kubernetes, Jenkins, and monitoring as operational artifacts.
7. Practice system design questions aloud using the answer framework.
8. Review AI-agent workflow questions and be ready to discuss verification and security.
