# Kafka Topics & Event Contracts — ShopSphere

Topic naming: `order-created`, `payment-completed` (dots → dashes at the
broker; producers write event_type with dots, relays map `.`→`-`).

## Topic map

| Topic | Producer | Consumers | Payload (key fields) |
|---|---|---|---|
| order-created | order-service (outbox) | inventory-service | orderId, orderNumber, customerId, totalAmount, items[] |
| order-confirmed | order-service | notification-service | orderId, orderNumber, totalAmount |
| order-cancelled | order-service | inventory, payment, notification | orderId, reason |
| order-failed | order-service | notification-service | orderId, reason |
| inventory-reserved | inventory-service (outbox) | payment-service | orderId, amount, customerId, reservations[] |
| inventory-failed | inventory-service | order-service | orderId, failures[] |
| payment-initiated | (reserved) | — | — |
| payment-completed | payment-service (outbox) | order-service, notification | paymentId, orderId, amount, gatewayRef |
| payment-failed | payment-service | order-service, notification | orderId, reason |
| payment-refunded | payment-service | notification-service | paymentId, orderId, amount, reason |
| product-events | product-service | (future: search indexer) | productId, sku, price, status |
| *.DLT | DeadLetterPublishingRecoverer | ops/replay | original payload + exception headers |

## Event envelope (every event)
`eventId` (UUID, idempotency key), `eventType`, domain fields,
`occurredAt` (producer clock), `version` (schema version, start at 1).

## Guarantees & semantics
- **Delivery**: at-least-once. Consumers MUST be idempotent — we use
  `processed_events` (DB) / Redis SETNX (notification) keyed on eventId.
- **Ordering**: per-key ordering only (partition key = aggregate id, e.g.
  orderId). Cross-aggregate ordering is NOT guaranteed — saga tolerates it.
- **Serialization**: JSON strings; consumers parse with ObjectMapper —
  no `__TypeId__` header coupling between independently deployed services.
- **Offsets**: `enable.auto.commit=false`, AckMode.RECORD — offset commits
  after the listener's DB tx commits; crash → redelivery → dedupe.
- **Retries**: consumers FixedBackOff(2s, 3); exhausted → `<topic>.DLT`.
- **Producer**: `acks=all`, `enable.idempotence=true` — no dupes on retry,
  no acks until ISR replication.

## Schema evolution rules
- Add optional fields only; never rename/remove without `version` bump.
- Consumers ignore unknown fields (Jackson default) — producers can evolve
  ahead of consumers safely.

## Troubleshooting
- `kafka-consumer-groups --describe` → lag growing = consumer down/slow.
- Check `<topic>.DLT` for poison messages (bad JSON, deser errors).
- Consumer stuck on one partition → hot key (single orderId flooding);
  increase partitions or shard key.
- Outbox `published_at IS NULL` growing → relay can't reach broker;
  check `last_error`/`attempts` columns.
