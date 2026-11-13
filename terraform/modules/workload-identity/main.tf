# Workload Identity — binds a K8s ServiceAccount to a GCP ServiceAccount so
# pods authenticate to GCP (Cloud SQL, Secret Manager) WITHOUT key files.
#
# Flow: Pod -> K8s SA (ksa_name) -> WI pool -> GSA (gsa_account_id) ->
#       roles/cloudsql.client + cloudsql.instanceUser -> Cloud SQL.
# Pods then connect via cloud-sql-proxy sidecar or the Go connector using
# the instance CONNECTION NAME (never an IP/password in manifests).
variable "project_id" { type = string }
variable "cluster_name" { type = string }
variable "namespace" { type = string }
variable "ksa_name" { type = string }
variable "gsa_account_id" { type = string }
variable "sql_instance" { type = string }

# GCP service account the pods impersonate.
resource "google_service_account" "app" {
  account_id   = var.gsa_account_id
  display_name = "CommerceHub ${var.namespace} app"
  project      = var.project_id
}

# Least-privilege roles: connect to Cloud SQL instances as IAM DB users.
resource "google_project_iam_member" "cloudsql_client" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.app.email}"
}

resource "google_project_iam_member" "cloudsql_instance_user" {
  project = var.project_id
  role    = "roles/cloudsql.instanceUser"
  member  = "serviceAccount:${google_service_account.app.email}"
}

# K8s ServiceAccount in the app namespace (apply via kubernetes provider).
resource "kubernetes_service_account" "app" {
  metadata {
    name      = var.ksa_name
    namespace = var.namespace
    annotations = {
      # The Workload Identity binding — this annotation is the whole link.
      "iam.gke.io/gcp-service-account" = google_service_account.app.email
    }
  }
}

# Allow the GKE workload pool to impersonate the GSA.
resource "google_service_account_iam_member" "workload_identity_user" {
  service_account_id = google_service_account.app.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[${var.namespace}/${var.ksa_name}]"
}

output "gsa_email" { value = google_service_account.app.email }
output "ksa_name" { value = kubernetes_service_account.app.metadata[0].name }
