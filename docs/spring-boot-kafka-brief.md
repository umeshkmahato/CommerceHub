# Spring Boot & Kafka — Interview Brief

Condensed study guide for the two most-weighted topics. Every concept maps
to something actually in this repo so you can point at code, not just theory.

---

## Part A — Spring Boot

### A.1 What it is
Spring Boot = opinionated starter layer over Spring Framework. Auto-configuration,
embedded Tomcat, externalized config, actuator. We use **Boot 3.3.5, Java 17**.

### A.2 Core annotations you'll be asked about
| Annotation | Where used here | What it does |
|---|---|---|
| `@SpringBootApplication` | every `*Application.java` | = `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan` |
| `@RestController` / `@Controller` | all controllers | MVC endpoint + JSON serialization |
| `@Service` | `*Service.java` | business-logic bean, transactional boundary |
| `@Repository` | Spring Data repos | DAO stereotype + exception translation |
| `@Component` | clients, filters, relays | generic bean |
| `@Bean` | `KafkaConfig`, `SecurityConfig`, `OpenApiConfig` | manual bean definition when auto-config isn't enough |
| `@Configuration` / `@ConfigurationProperties` | config classes | bean wiring / typed config binding |
| `@Transactional` | service methods | ACID boundary; `readOnly=true` skips dirty-checking |
| `@Scheduled` | `OutboxRelay.relay()` | fixed-delay poller for unpublished outbox rows |
| `@KafkaListener` | all `*EventConsumer.java` | message-driven consumer entry point |
| `@EnableScheduling`, `@EnableCaching`, `@EnableAsync`, `@EnableWebSecurity`, `@EnableMethodSecurity` | app/config classes | activate framework features |

### A.3 Dependency injection & beans
- **Constructor injection** everywhere (no `@Autowired` fields) — testable,
  immutable, fails fast on missing beans.
- **IoC container** creates singletons; circular deps are a design smell.
- `@Value` injects config; `${ENV:default}` relaxed binding (env var →
  `POSTGRES_HOST` maps to `postgres.host`-style properties).

### A.4 Spring MVC request lifecycle (what happens on `POST /api/v1/orders`)
1. `DispatcherServlet` receives request → `HandlerMapping` finds controller.
2. Argument resolvers bind `@RequestHeader`, `@RequestBody` (+ `@Valid` →
   `MethodArgumentNotValidException` → 400 via `@RestControllerAdvice`).
3. Service `@Transactional` method runs: cart fetch → order + items +
   outbox row in ONE tx.
4. `HttpMessageConverter` serializes response; exceptions bubble to
   `GlobalExceptionHandler` → standard `ErrorResponse` envelope.

### A.5 Spring Data JPA essentials
- `JpaRepository<Entity, ID>` gives CRUD + paging + derived queries
  (`findByCustomerIdOrderByCreatedAtDesc`).
- **`ddl-auto: validate`** — Hibernate checks entities match Flyway schema;
  Flyway owns DDL. Never `update` in real services.
- **Entity states**: transient → managed (persist) → detached (after tx).
  Mutating a detached entity does nothing — this was a real bug class in
  the payment retry path; mutations belong inside `@Transactional`.
- **Fetch strategies**: `LAZY` default for `@OneToMany`/`@ManyToOne` optional;
  `EAGER` only for always-needed small sets (roles). `@EntityGraph` /
  `JOIN FETCH` prevent N+1.
- **`@Version`** = optimistic locking; `SELECT ... FOR UPDATE` = pessimistic
  (inventory stock). Choosing between them is a top interview question.

### A.6 Spring Security (user-service)
- `SecurityFilterChain` bean: `STATELESS` sessions, CSRF off (correct for
  Bearer-token APIs — CSRF targets cookies), `DaoAuthenticationProvider` +
  `BCryptPasswordEncoder(10)`, `JwtAuthFilter` before
  `UsernamePasswordAuthenticationFilter`.
- `@PreAuthorize("hasRole('ADMIN')")` via `@EnableMethodSecurity`.
- JWT: `jjwt` 0.12, **pinned `Jwts.SIG.HS256`** (auto-select picks HS384 →
  `WeakKeyException` on 256-bit secrets — a real bug we hit).
- Refresh tokens: opaque 256-bit random, stored as **SHA-256 hash**,
  rotated per use, reuse → revoke all user tokens.

### A.7 Spring Cloud Gateway (api-gateway)
- WebFlux (reactive) — non-blocking proxy, right for I/O-bound routing.
- `GlobalFilter` + `Ordered(-100)`: strips `X-User-Id`/`X-User-Roles`,
  validates JWT, injects trusted headers, 401s protected paths.
- Routes = YAML predicates (`Path=/api/v1/orders/**` → order-service).
- `RequestRateLimiter` + `RedisRateLimiter` = distributed rate limiting
  (in-memory would multiply limits per pod — classic scaling bug).

### A.8 Resilience4j
- `@CircuitBreaker(name="productService", fallbackMethod=...)` on
  `ProductClient.getProduct` — opens on failure threshold, half-open probe,
  fallback → controlled 503 instead of thread exhaustion.
- Pairs with explicit RestClient timeouts (connect 1.5s / read 3s).

### A.9 Actuator & config
- `/actuator/health/{liveness,readiness}` — K8s probes; `probes.enabled=true`.
- `/actuator/prometheus` exposed (needs `micrometer-registry-prometheus`
  dependency — flagged as a gap to verify).
- Config hierarchy: `application.yml` defaults ← env vars ← K8s
  ConfigMap/Secret. `.env.example` documents required vars.

### A.10 Spring Kafka basics
- `@KafkaListener(topics=..., groupId=..., containerFactory=...)`
- `ConcurrentKafkaListenerContainerFactory` — concurrency = partitions.
- `ConsumerFactory` props: `ENABLE_AUTO_COMMIT=false` (we commit manually
  after tx), `AckMode.RECORD`, `AUTO_OFFSET_RESET=earliest`.
- `KafkaTemplate` for producing; `DeadLetterPublishingRecoverer` +
  `DefaultErrorHandler` + `FixedBackOff` for retry→DLT.

### A.11 Interview rapid-fire (Spring Boot)
1. **Why `@Transactional` on service, not controller?** Transaction boundary
   belongs to business logic; controllers stay thin and framework-agnostic.
2. **Self-invocation problem?** Calling `this.method()` bypasses the proxy →
   no transaction/caching. Fix: inject self or split beans.
3. **`readOnly=true` benefit?** Skips dirty-checking → cheaper reads; still
   gets connection + isolation guarantees.
4. **Bean scopes?** Singleton default; `@RequestScope`/`@SessionScope`/
   `@Async` proxies exist but add complexity — justify before using.
5. **`@Async` caveat?** Return `CompletableFuture`; exceptions don't propagate
   to caller; needs `@EnableAsync` + thread pool tuning.
6. **Actuator security?** Expose only needed endpoints; never expose
   `/env`, `/heapdump`, `/beans` publicly.
7. **Why constructor injection?** Immutability, testability, no null surprises.
8. **Spring vs Spring Boot?** Boot = auto-config + starters + embedded
   server; Spring = the framework underneath.
9. **`@Profile`?** `SPRING_PROFILES_ACTIVE=docker/k8s` loads
   `application-docker.yml` overrides — env-specific config without code changes.
10. **Bean validation timing?** `@Valid` runs BEFORE method body — a 403 check
    inside the method never sees an invalid body (real test bug we hit).

---

## Part B — Kafka

### B.1 What it is
Distributed append-only commit log. Topics → partitions → offsets. Consumer
groups track position independently. Producers write, brokers replicate,
consumers poll in pull-based model.

### B.2 Core concepts
- **Topic/Partition**: parallelism + ordering unit. Ordering guaranteed
  **per partition only**; we key by `orderId` so one order's events stay ordered.
- **Offset**: consumer position. We use `enable.auto.commit=false` +
  `AckMode.RECORD` → offset commits after listener's DB tx commits.
- **Consumer group**: independent subscribers; adding a consumer = new group
  gets full replay (`earliest`).
- **Replication/ISR**: durability; `acks=all` waits for ISR replication.
- **Retention**: time/size-based; replay possible within window.

### B.3 Our delivery guarantees (say this precisely)
- **Producer**: `acks=all`, `enable.idempotence=true` → no dupes on retry,
  no ack until replicated.
- **Delivery**: **at-least-once** (never claim exactly-once).
- **Consumers**: idempotent via `processed_events` table (DB services) or
  Redis `SETNX` (notification). Effect + dedupe marker in ONE transaction.
- **Ordering**: per-key only (partition key = orderId). Cross-order ordering
  NOT guaranteed — saga must tolerate it.
- **Failures**: `FixedBackOff(2s, 3 retries)` → `DeadLetterPublishingRecoverer`
  → `<topic>.DLT` with exception headers.

### B.4 The outbox pattern (our producer side)
```
@Transactional method:
  1. save domain entity (order/payment/reservation)
  2. save OutboxEvent row (same DB tx → atomic)
Commit → @Scheduled OutboxRelay:
  3. SELECT unpublished FOR UPDATE
  4. kafkaTemplate.send(topic, key=aggregateId, payload).get()
  5. markPublished() — crash between 4&5 → redelivery → consumer dedupes
```
Why: kills the dual-write problem (DB commit ≠ Kafka send can't be atomic).
`recordFailure()` backs off `available_at` exponentially (5s→5min cap).

### B.5 Consumer correctness rules
1. **Idempotent**: dedupe by `eventId` BEFORE side effects.
2. **Dedupe + effect in same tx**: `processed_events` insert commits with
   the business change — crash mid-way rolls back both.
3. **No offset commit before tx**: `AckMode.RECORD` + auto-commit off.
4. **DLQ, don't drop**: poison messages → `.DLT` for inspection/replay.
5. **Schema evolution**: additive fields only; consumers ignore unknown
   JSON fields; `version` field in envelope.

### B.6 Topic map (from `docs/kafka-topics.md`)
`order-created → inventory-service`; `inventory-reserved → payment-service`;
`payment-completed/failed → order-service + notification`;
`order-cancelled → inventory + payment + notification`; `*.DLT` for ops.

### B.7 Kafka vs alternatives
| | Kafka | RabbitMQ | SQS/PubSub |
|---|---|---|---|
| Model | log/replay | queue/delete | queue |
| Ordering | per-partition | per-queue | per-group |
| Replay | yes (retention) | no | limited |
| Throughput | very high | high | high |
| Ops complexity | higher | lower | lowest |
| Fit | event sourcing, replay, high volume | task queues, RPC-ish | serverless |

### B.8 Interview rapid-fire (Kafka)
1. **At-least-once vs exactly-once?** Kafka offers EOS via transactions, but
   end-to-end exactly-once needs consumer-side idempotency anyway — we do
   at-least-once + idempotent consumers (the pragmatic answer).
2. **Why `acks=all`?** Durability — leader waits for all ISR replicas.
   `acks=1` risks loss on leader failure.
3. **Why manual offset commit?** Auto-commit can skip/dup on crash; committing
   after tx = process-then-ack = safe redelivery.
4. **Hot partition problem?** One orderId = one partition; a celebrity SKU or
   single customer can skew. Fix: more partitions, composite keys, or
   per-partition parallelism limits.
5. **Consumer lag growing — why?** Slow processing, DB locks, downstream
   timeouts, or too few consumers (max = partition count). Check
   `kafka-consumer-groups --describe`.
6. **DLQ purpose?** Poison messages don't block the partition; ops replays
   after fix. Needs alerting — a DLQ is not a trash can.
7. **Partition count trade-off?** More = more parallelism + more ordering
   boundaries; can't easily decrease later.
8. **Why JSON not Avro/Protobuf?** Language-neutral, human-readable, schema
   registry optional; trade-off = weaker schema enforcement. Additive
   evolution works because Jackson ignores unknown fields.
9. **Kafka vs REST for saga?** Events decouple lifecycle — services come and
   go independently; REST couples availability. But saga needs BOTH: sync
   for request-time validation, async for workflow progression.
10. **Idempotent producer?** `enable.idempotence=true` dedupes producer
    retries via sequence numbers — prevents dup sends on network retry.

---

## Part C — Docker

### C.1 Core concepts
- **Image vs container**: image = immutable build artifact (layers); container
  = running instance. We build one image per service via multi-stage Dockerfiles.
- **Multi-stage build**: `maven:3.9-temurin-17` builds jar →
  `eclipse-temurin:17-jre-alpine` runs it. Keeps runtime image small (~200MB
  vs ~500MB), fewer CVEs, non-root `USER app`.
- **Layers & caching**: `COPY pom.xml` → `dependency:go-offline` → `COPY src`
  ordering means dep layer caches unless pom changes. Big CI speedup.
- **JVM-in-container tuning**: `-XX:MaxRAMPercentage=75.0` + `-XX:+UseG1GC` —
  container-aware memory; without it JVM sees host RAM and gets OOM-killed.
- **EXPOSE vs publish**: EXPOSE documents port; `-p`/compose `ports:` maps host.

### C.2 Docker Compose (our `docker-compose.yml`)
- **Services**: 2 DBs (mysql:8.4, postgres:16), kafka (KRaft, no Zookeeper —
  `KAFKA_PROCESS_ROLES=broker,controller`), `kafka-init` one-shot topic
  creation, redis:7.4, 8 Java services, web (nginx).
- **Healthchecks**: `mysqladmin ping`, `pg_isready`, `kafka-topics --list`,
  `redis-cli ping` — infra must be *healthy*, not just started.
- **`depends_on: condition: service_healthy`**: ordering without race
  conditions — apps don't boot before Flyway can reach the DB.
- **Init scripts**: `docker-entrypoint-initdb.d/` runs once on first boot —
  creates 3 MySQL DBs + per-service users, 3 Postgres DBs + roles.
- **Volumes**: `mysql-data`, `pg-data` persist data across restarts.
- **Per-service DB users**: least privilege — `product` user can't touch
  `orderdb`. Matches database-per-service ownership.
- **Networks**: default compose network = DNS by service name
  (`postgres`, `kafka:9092`) — same names K8s uses later.

### C.3 Dockerfile best practices (what interviewers probe)
1. Multi-stage: build deps in fat image, run in slim JRE.
2. Non-root user — container escape impact reduction.
3. `.dockerignore` for `target/`, `.git` — smaller context, faster builds.
4. Pin base image tags (`mysql:8.4`, not `latest`) — reproducibility.
5. `HEALTHCHECK` or rely on orchestrator probes.
6. No secrets in image — env vars at runtime only.
7. `dependency:go-offline` layer for cache efficiency.

### C.4 Docker interview rapid-fire
1. **Image layers?** Each instruction = layer; shared base layers cached;
   order cheap-changing instructions last.
2. **`CMD` vs `ENTRYPOINT`?** ENTRYPOINT = fixed executable, CMD = default args
   (can be overridden). We use ENTRYPOINT exec-form (no shell → proper signals).
3. **Why alpine JRE?** Smaller attack surface + size; trade-off = musl libc,
   occasionally missing libs — verify native deps.
4. **Container vs VM?** Containers share host kernel (namespaces + cgroups) —
   lighter, faster startup; VMs need full OS + hypervisor.
5. **How does JVM know container memory?** Modern JVMs are container-aware;
   `MaxRAMPercentage` sets heap as % of cgroup limit — prevents OOM kills.
6. **`depends_on` healthy vs started?** `service_healthy` waits for healthcheck
   — prevents Flyway/app crash-loops on boot race.
7. **Docker vs Kubernetes?** Docker = packaging/runtime primitives; K8s =
   orchestration (scheduling, self-healing, scaling, service discovery).
   Compose = local dev orchestration.
8. **How do you debug a crashing container?** `docker logs`, `docker exec`,
   check exit code, healthcheck status, env vars, mounted volumes.

---

## Part D — Kubernetes

### D.1 Core objects (map to our `deploy/k8s/`)
| Object | Our file | What it does |
|---|---|---|
| Namespace | `namespace.yaml` | isolation boundary — all shopsphere resources |
| ConfigMap | `configmap.yaml` | non-secret env (Kafka/Redis/DB hosts, TTLs) |
| Secret | `secret.example.yaml` | JWT secret, DB passwords — `kubectl create`, never git |
| Deployment | `apps.yaml` | desired pod count + rollout strategy |
| ReplicaSet | (created by Deployment) | owns pods, handles rolling updates |
| Pod | (created by RS) | smallest unit — our container + probes |
| Service | `apps.yaml` | stable DNS + ClusterIP load balancing |
| Ingress | `ingress.yaml` | HTTP entry → web service, nginx class |
| HPA | `hpa.yaml` | CPU-based autoscaling (needs metrics-server) |
| Probes | in Deployments | readiness/liveness via actuator health |

### D.2 Pod lifecycle & controllers
- **Deployment → ReplicaSet → Pod**: Deployment manages ReplicaSets which own
  Pods. Rolling update = new RS scales up, old scales down (maxUnavailable 25%).
- **Self-healing**: pod dies → RS recreates it. Node dies → pod rescheduled.
- **`kubectl rollout undo`** — previous ReplicaSet retained = instant rollback.

### D.3 Networking
- **Service DNS**: `user-service` resolves cluster-wide (same name as Compose
  service — deliberate continuity).
- **ClusterIP** (default): internal LB, no external IP.
- **Ingress**: single HTTP(S) entry point; nginx ingress controller routes
  to Services; handles TLS termination + rate limiting.
- **NetworkPolicy** (not yet in manifests): restrict pod-to-pod traffic —
  should be added for production (gateway-only ingress to services).

### D.4 Probes — the most-asked K8s topic
- **readinessProbe**: "ready for traffic?" Failing → removed from Service
  endpoints (pod stays alive). We point at `/actuator/health/readiness`.
- **livenessProbe**: "alive?" Failing → container restarted. We point at
  `/actuator/health/liveness`.
- **startupProbe** (not yet used): for slow-starting apps — delays liveness
  until app boots, prevents restart loops on Flyway migrations.
- Why actuator groups: liveness = "is JVM alive", readiness = "can it serve"
  (includes DB/Kafka/Redis health indicators).

### D.5 Config & secrets
- **ConfigMap**: `envFrom: configMapRef` injects all keys as env vars —
  non-secret shared config, editable without rebuild.
- **Secret**: same mechanism but base64; etcd encryption at rest; RBAC
  restricts read; NEVER commit real values (we ship `secret.example.yaml`).
- **12-factor**: config in env, not baked into image — same jar runs
  anywhere.

### D.6 Scaling & resilience
- **HPA**: scales Deployment replicas on CPU/memory/custom metrics.
  Our `hpa.yaml`: product-service 2→10, gateway 2→6 @ 70% CPU.
- **PodDisruptionBudget** (future): min available pods during node drains.
- **Resource requests/limits**: requests = scheduling guarantee, limits =
  max before OOMKill/throttle. We set both per service.
- **Stateless services scale freely**; stateful (DBs) use managed services
  or operators (CloudNativePG, Strimzi for Kafka, Bitnami Helm for Redis).

### D.7 Our K8s manifests — honest assessment
`apps.yaml` has full Deployments+Services for all 9 components with probes,
resources, env wiring. **Gaps for production**: no StatefulSets for DBs
(should be managed), no NetworkPolicies, no PodDisruptionBudgets, no
startupProbes, no initContainers for migration ordering, secrets are a
template, no GitOps (ArgoCD/Flux), images tagged `1.0.0` not `BUILD_NUMBER`.

### D.8 Interview rapid-fire (Kubernetes)
1. **Pod vs Deployment vs ReplicaSet?** Pod = running unit; RS = pod
   lifecycle/replicas; Deployment = declarative rollout on top of RS.
2. **Readiness vs liveness?** Readiness gates traffic; liveness restarts
   dead containers. Wrong liveness = restart loops.
3. **Service types?** ClusterIP (internal), NodePort (node port), LoadBalancer
   (cloud LB), ExternalName (DNS alias).
4. **ConfigMap vs Secret?** Both inject env; Secret adds encryption-at-rest
   expectation + access control. Neither is truly secret from anyone with
   pod exec — use external secret stores (Vault, ESO) for real secrets.
5. **HPA metrics?** CPU/memory (built-in) or custom via metrics-server/
   Prometheus adapter — we use CPU 70%.
6. **Rolling update strategy?** maxSurge/maxUnavailable control rollout
   speed vs availability; default 25%/25%.
7. **What happens on node failure?** Pods rescheduled to healthy nodes
   (if replicas >1 and resources allow); StatefulSet pods reattach volumes.
8. **Init containers?** Run before app (wait-for-db, run-migration) —
   we should add one for Flyway ordering in K8s.
9. **K8s vs Compose?** Compose = single-host dev orchestration; K8s =
   multi-node production orchestration with self-healing, scheduling,
   rolling updates, service discovery.
10. **Namespace purpose?** Resource isolation, RBAC scoping, quota —
    our `shopsphere` namespace groups everything.

---

## Part E — Docker + Kubernetes together (the interview arc)

**The narrative**: "Each service has a multi-stage Dockerfile producing a
small non-root image with health-aware JVM flags. Compose wires them locally
with healthcheck-ordered startup and per-service DB users. The same images
deploy to K8s where Deployments add self-healing replicas, Services replace
Compose DNS, ConfigMap/Secret replace env files, probes replace Compose
healthchecks for traffic gating, and HPA adds CPU autoscaling. The container
is the unit of deployment in both worlds — that's the portability story."

**Likely follow-ups**:
- *How do migrations run in K8s?* → initContainer or Job before app rollout;
  Flyway runs on app boot (`spring.flyway.enabled=true`) but ordering needs
  the DB ready first — initContainer waits for postgres Service.
- *How do services find each other?* → Service DNS names identical to Compose
  (`user-service`, `postgres`) — ConfigMap injects same hostnames.
- *What breaks first at scale?* → DB connection pools, Kafka partitions,
  single-point Redis for rate limiting/cache — mitigate with managed
  services, partition planning, Redis HA.

---

## How to answer "tell me about Spring Boot/Kafka/Docker/K8s experience" in 90 seconds

> "I built an eight-service e-commerce platform on Spring Boot 3.3/Java 17 —
> MVC services with JPA, Flyway, Spring Security JWT, and Spring Cloud
> Gateway for edge auth and rate limiting. For messaging I used Spring Kafka
> with consumer groups, manual offset commits tied to DB transactions, retry
> with backoff, and dead-letter topics. The interesting part was correctness:
> every consumer is idempotent via a processed-events table, producers use a
> transactional outbox so DB writes and events can't diverge, and the checkout
> saga runs as choreography across order, inventory, and payment services.
> I can walk you through the outbox relay, the FOR UPDATE stock reservation,
> and how a payment timeout is handled. Each service ships as a multi-stage
> non-root container image; Docker Compose runs the full stack locally with
> healthcheck-ordered startup and per-service database users, and the same
> images deploy to Kubernetes where Deployments give self-healing replicas,
> Services give stable DNS, probes gate traffic on actuator health, and HPA
> autoscales on CPU. Config externalizes through env vars → ConfigMap/Secret.
> Happy to go deep on any layer."
