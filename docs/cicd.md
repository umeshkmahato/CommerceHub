# CI/CD — Jenkins Pipeline

## Stages & why each exists

| Stage | Purpose | Failure mode it catches |
|---|---|---|
| Checkout | Source | — |
| Build & Unit Test | `mvn verify` — compile + JUnit/Mockito/MockMvc | Logic regressions, fast feedback (~min) |
| Integration Tests | `*IT` classes — Testcontainers vs real MySQL/Postgres | Schema/ORM drift, Flyway breakage |
| Code Quality | SpotBugs/CheckStage gate (placeholder) | Security smells, dead code |
| Build & Scan | Docker build all 9 images + `trivy` CRITICAL scan | Vulnerable base images/deps — blocks push |
| Push | Registry push with credentialed login | — |
| Deploy Staging | `kubectl set image` rolling update | — |
| Smoke Verify | rollout status + live `/api/v1/products` curl | Broken deploy caught before prod |

## Interview talking points
- **Shift-left**: tests run before image build; image scan before push —
  vulnerabilities never reach the registry.
- **Immutable artifacts**: image tagged with `BUILD_NUMBER`, promoted
  staging→prod unchanged (no rebuild per env).
- **Credentials**: registry login via Jenkins credentials store — never
  literals in the Jenkinsfile.
- **Rollback**: `kubectl rollout undo` (previous ReplicaSet retained);
  Jenkins `input` step gates prod promotion.
- **Real-world upgrade**: replace `kubectl set image` with Helm/ArgoCD
  GitOps; add canary deploys via Flagger; SAST (SonarQube) + DAST stages.

## Local equivalent (no Jenkins)
```bash
mvn clean verify                      # unit + IT (needs Docker)
docker compose up --build             # same images Compose builds
```
