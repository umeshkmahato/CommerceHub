# Kubernetes Deployment — ShopSphere

## Concepts used (interview checklist)
- **Namespace** `shopsphere` — isolation boundary for all resources.
- **Deployment → ReplicaSet → Pod** — Deployment manages ReplicaSets which
  own Pods; rolling updates replace pods gradually (maxUnavailable default 25%).
- **Service** — stable DNS name + virtual IP load-balancing across pods.
  `user-service` resolves cluster-wide; this replaces Compose service names.
- **ConfigMap** `shopsphere-config` — non-secret env (hosts, ports, TTLs).
- **Secret** `shopsphere-secrets` — JWT secret, DB passwords. NEVER in git;
  create via `kubectl create secret` (see secret.example.yaml).
- **Probes** — `readinessProbe` (is it ready for traffic? removes from Service
  endpoints) vs `livenessProbe` (is it alive? restarts container). Both use
  Spring actuator `/actuator/health/{readiness,liveness}` (enabled via
  `management.endpoint.health.probes.enabled=true`).
- **HPA** — CPU-based autoscaling; requires metrics-server. Product/gateway
  scale out; order/payment stay modest (saga ordering > raw throughput).
- **Ingress** — single HTTP entry → web service; nginx class with rate limit.

## Deploy

```bash
kubectl create namespace shopsphere
kubectl apply -f deploy/k8s/namespace.yaml
# create secret FIRST (apps won't start without it):
kubectl -n shopsphere create secret generic shopsphere-secrets \
  --from-literal=JWT_SECRET=$(openssl rand -base64 48) \
  --from-literal=MYSQL_ROOT_PASSWORD=... \
  --from-literal=POSTGRES_PASSWORD=...
kubectl apply -f deploy/k8s/configmap.yaml deploy/k8s/apps.yaml
kubectl apply -f deploy/k8s/ingress.yaml deploy/k8s/hpa.yaml
```

Stateful infra (MySQL/Postgres/Kafka/Redis) should use managed services or
operators (CloudNativePG, Strimzi, Bitnami Helm) in real clusters — the app
deployments reference their DNS names via ConfigMap.

## Day-2 operations

```bash
kubectl -n shopsphere rollout status deploy/order-service
kubectl -n shopsphere rollout undo deploy/order-service      # rollback
kubectl -n shopsphere logs deploy/order-service -f --tail=100
kubectl -n shopsphere describe pod <pod>                     # probe failures
kubectl -n shopsphere top pods                                # needs metrics-server
kubectl -n shopsphere get hpa                                 # autoscaler state
```

## Troubleshooting
- Pod CrashLoopBackOff → `logs` + `describe` (usually missing secret/env or DB unreachable).
- Readiness failing → app can't reach its DB/Kafka; check ConfigMap hostnames.
- 502 from ingress → Service selector labels mismatch app labels.
- HPA not scaling → metrics-server missing (`kubectl top nodes` fails).
