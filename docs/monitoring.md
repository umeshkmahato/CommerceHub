# Monitoring & Resilience — ShopSphere

## Metrics pipeline
Every service exposes `/actuator/prometheus` (micrometer). `deploy/monitoring/prometheus.yml`
scrapes all 8 services + gateway; `grafana-dashboard.json` shows request rate,
5xx %, JVM heap, Kafka consumer lag. In K8s use prometheus-operator +
ServiceMonitors instead of static targets.

Key dashboards/alerts to build:
- `rate(http_server_requests_seconds_count[5m])` per service → traffic
- 5xx ratio > 1% for 5m → page
- `jvm_memory_used_bytes / jvm_memory_used_max_bytes` > 85% → heap pressure
- `kafka_consumer_group_lag` growing → consumer behind (check DLQ + logs)
- HikariCP pending threads → DB pool exhaustion

## Distributed tracing
Spring Boot 3 + Micrometer Tracing (OTel/Brave) — add
`micrometer-tracing-bridge-otel` + OTLP exporter to each service; trace_id
propagates through RestClient calls and Kafka headers automatically.
Enable with `management.tracing.sampling.probability=1.0` (dev) / 0.1 (prod).

## Resilience patterns implemented
- **Timeouts**: RestClient connect 1.5s / read 3s (cart→product, order→cart).
- **Circuit breaker**: `@CircuitBreaker(name="productService")` on
  ProductClient.getProduct — opens after failure threshold, half-open probe,
  fallback maps to 503. Config in `resilience4j.circuitbreaker.*` yml.
- **Retry with backoff**: Kafka consumers (FixedBackOff 2s×3) + outbox relay
  (`available_at` exponential backoff 5s→5min cap).
- **Bulkhead**: HikariCP pool sizes cap concurrent DB work per pod.
- **Rate limit**: gateway RedisRateLimiter 100 req/min/IP.

## Logging
Structured JSON logs (logstash encoder) → Loki/ELK; trace_id in MDC per
request. Centralized logs are essential when a saga spans 4 services —
grep by `orderId`/`eventId` reconstructs the whole flow.

## Health
`/actuator/health/{liveness,readiness}` — K8s probes use these; readiness
includes DB/Kafka/Redis indicators so pods only take traffic when deps are up.
