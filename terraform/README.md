# CommerceHub — Terraform Guide

This directory provisions the **cloud infrastructure** for CommerceHub on
Google Cloud Platform: a GKE cluster, a Cloud SQL PostgreSQL instance, and
the identity plumbing that lets Kubernetes pods connect to the database
**without passwords or key files**.

It does **not** deploy the application containers — that's the job of the
Kubernetes manifests in `deploy/k8s/` (applied by Jenkins/ArgoCD after
`terraform apply`). Terraform creates the *platform*; the app manifests
create the *workloads* on top of it.

---

## 1. The big picture

```
┌──────────────────────────── GCP Project ────────────────────────────┐
│                                                                     │
│   Terraform Cloud (TFC) workspaces                                  │
│   ┌──────────────────┐         ┌──────────────────┐                 │
│   │ commercehub-dev  │         │ commercehub-prod │                 │
│   │  VCS → dev branch│         │  VCS → main      │                 │
│   └────────┬─────────┘         └────────┬─────────┘                 │
│            │ plan/apply                │ plan/apply                 │
│            ▼                            ▼                           │
│   environments/dev/              environments/prod/                 │
│   (root module)                  (root module)                      │
│            │                            │                           │
│            └──────────┬─────────────────┘                           │
│                       ▼                                             │
│              modules/  (reusable building blocks)                   │
│   ┌──────────┐  ┌───────────────┐  ┌─────────────────────┐          │
│   │ gke      │  │ cloudsql-db   │  │ workload-identity   │          │
│   │ cluster  │  │ PG instance + │  │ GSA + K8s SA + IAM  │          │
│   │          │  │ 3 DBs + users │  │  binding            │          │
│   └──────────┘  └───────────────┘  └─────────────────────┘          │
│                                                                     │
│   Result: pods authenticate to Cloud SQL via Workload Identity      │
│   (no DB passwords, no service-account keys in the cluster).        │
└─────────────────────────────────────────────────────────────────────┘
```

## 2. Directory layout

```
terraform/
├── README.md                        ← you are here
├── environments/
│   ├── dev/                         # ROOT for dev
│   │   ├── main.tf                  # providers + module calls + outputs
│   │   ├── variables.tf             # all variable declarations + module wiring
│   │   ├── dev.auto.tfvars.example  # copy → fill real values (gitignored)
│   │   └── backend config           # inside main.tf: remote backend block
│   └── prod/                        # ROOT for prod — identical shape,
│                                    # different defaults (tier, nodes, backups)
└── modules/
    ├── gke/                         # GKE Autopilot cluster + Workload Identity pool
    ├── cloudsql-db/                 # Cloud SQL PG16 instance, 3 databases,
    │                                # per-service users, private VPC access
    └── workload-identity/           # GCP SA + IAM roles + K8s SA + binding
```

**Key Terraform concept — root vs module:**

- A **root module** is a directory you run `terraform init/plan/apply` in.
  Ours are `environments/dev` and `environments/prod`.
- A **module** is a reusable component called with inputs. Ours live in
  `modules/` and contain no backend — they're just resources + variables.
- The root *chooses* which modules to instantiate and *passes inputs*
  (e.g., dev uses `db-f1-micro`, prod uses `db-custom-1-3840`).

## 3. How TFE (Terraform Cloud) drives this

Each environment is a separate **TFC workspace** with a **VCS connection**
to this repo:

| Workspace | VCS branch/path | Trigger |
|---|---|---|
| `commercehub-dev` | dev branch → `environments/dev/` | PR = speculative plan; merge = apply |
| `commercehub-prod` | main/prod branch → `environments/prod/` | PR = plan; approved merge = apply |

The `backend "remote"` block inside each `main.tf` tells Terraform to store
state in TFC instead of locally. State is the record of what exists; never
edit it by hand, never commit it.

**Typical workflow:**

1. Change `environments/dev/variables.tf` or a module.
2. Open a PR → TFC runs `terraform plan` automatically and posts the diff.
3. Review the plan (adds/changes/destroys, especially anything `destroy`).
4. Merge → TFC applies.
5. Prod requires a separate workspace + approval — dev never touches prod.

## 4. What each module creates

### `modules/gke` — the Kubernetes cluster
- GKE **Autopilot** cluster (Google manages nodes; you only pay for pods).
- Private nodes; `workload_identity_config` enabled — this is what makes the
  auth story below possible.
- Outputs: `endpoint`, `ca_certificate`, `cluster_name` — consumed by the
  `kubernetes` provider so Terraform can also manage K8s objects.

### `modules/cloudsql-db` — the database
- One **Cloud SQL for PostgreSQL 16** instance (private IP only — no public
  endpoint), tier/disk/backups configurable per environment.
- Three databases: `userdb`, `orderdb`, `paymentdb` — matching the
  database-per-service design (MySQL services like product/cart/inventory
  would get their own instances or stay on Compose for now).
- Per-service DB users (`user_svc`, `order_svc`, `payment_svc`) — least
  privilege; real passwords come from TFC sensitive variables.
- Private Services Access: VPC peering so Cloud SQL is reachable inside the
  cluster's network without a public IP.

### `modules/workload-identity` — the auth bridge
This is the piece that answers "how do pods prove who they are to GCP?"

```
Pod ──uses──▶ K8s ServiceAccount "commercehub-app"
                 │ annotation: iam.gke.io/gcp-service-account = <GSA email>
                 ▼
        GKE Workload Identity pool (project.svc.id.goog)
                 │ allowed to impersonate (roles/iam.workloadIdentityUser)
                 ▼
        GCP ServiceAccount "commercehub-<env>-app"
                 │ has roles: cloudsql.client + cloudsql.instanceUser
                 ▼
        Cloud SQL — pod connects via cloud-sql-proxy sidecar or
        the Cloud SQL Go connector using the instance CONNECTION NAME.
```

**Why this matters:** no JSON key files mounted into pods, no DB passwords
in Kubernetes Secrets, automatic credential rotation, and IAM audit logs
show exactly which workload touched the database.

## 5. Variables — what you must supply

| Variable | Where | Notes |
|---|---|---|
| `project_id` | tfvars / TFC vars | your GCP project |
| `region` | default `us-central1` | pick near your users |
| `db_tier`, `db_disk_gb`, `db_backup_enabled` | per-env | dev: micro/10GB/no backups; prod: custom/50GB/daily |
| `deletion_protect` | derived | `true` in prod — prevents accidental `terraform destroy` |
| DB user passwords | **TFC sensitive variables** | never in git |

## 6. First-time setup checklist

```bash
# 1. Authenticate to TFC
terraform login

# 2. In TFC UI: create workspaces commercehub-dev + commercehub-prod,
#    connect each to this repo + the right branch/path.

# 3. Enable APIs in the GCP project (or let Terraform create them):
gcloud services enable container.googleapis.com sqladmin.googleapis.com \
  servicenetworking.googleapis.com iam.googleapis.com

# 4. Local dry-run (state still remote via backend):
cd terraform/environments/dev
cp dev.auto.tfvars.example dev.auto.tfvars   # fill project_id
terraform init
terraform plan                               # review carefully
```

Then let TFC own applies — don't `terraform apply` locally against prod.

## 7. Day-2 operations

```bash
terraform plan -out=tfplan     # save a reviewed plan
terraform apply tfplan         # apply exactly what was reviewed
terraform output               # db_connection_name, gke_endpoint, etc.
terraform destroy              # dev only — prod has deletion_protection
```

- **Scaling DB**: change `db_tier`/`db_disk_gb` in the env root → plan shows
  in-place resize (Cloud SQL resizes without downtime for disk; tier changes
  may restart — check the plan).
- **Adding a database**: append to `local.databases` in the env root.
- **Rotating DB passwords**: update TFC sensitive var → apply → restart pods.
- **Drift**: if someone changes infra in the GCP console, `terraform plan`
  shows the diff; reconcile by re-applying or importing — don't hand-edit.

## 8. Known gaps (be honest in interviews)

- DB user **passwords are placeholders** — need a `postgresql` provider or
  `gcloud sql users set-password` step; Terraform's google provider doesn't
  manage per-database GRANTs cleanly.
- Uses the **`default` VPC** — production should create a dedicated VPC
  module with firewall rules, private Google access, and Cloud NAT.
- MySQL databases (productdb/cartdb/inventorydb) aren't provisioned here —
  they'd be a second `cloudsql-db`-style module with `MYSQL_8_4`, or AlloyDB.
- No Terraform for Redis/Kafka — managed Memorystore + Managed Kafka are
  separate modules worth adding.
- **Not yet `terraform validate`d** — HCL is written but unverified against
  provider schemas; run `terraform validate` before first apply.
- Secrets: TFC workspace variables marked *sensitive* + state encryption —
  never commit `*.tfvars` with real values (only `*.example` files are tracked).

## 9. How to explain this in an interview

> "I structured Terraform as environment roots over reusable modules — dev
> and prod are separate TFC workspaces with VCS-driven plan/apply, so every
> PR gets a speculative plan and merges apply automatically. The app module
> provisions Cloud SQL Postgres with one database per service, and pods
> authenticate through Workload Identity: a K8s ServiceAccount annotated to a
> GCP ServiceAccount with `cloudsql.client`/`instanceUser` roles. That gives
> us IAM-based DB auth with no key files or passwords in the cluster, plus
> audit logging on every connection. Prod adds deletion protection, backups,
> and a larger tier through the same module inputs."
