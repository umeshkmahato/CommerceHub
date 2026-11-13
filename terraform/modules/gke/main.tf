# GKE Autopilot cluster — simplest secure default; node pool managed by Google.
variable "project_id" { type = string }
variable "region" { type = string }
variable "cluster_name" { type = string }
variable "node_count" { type = number }
variable "machine_type" { type = string }

resource "google_container_cluster" "main" {
  name     = var.cluster_name
  location = var.region
  project  = var.project_id

  # Autopilot: no node_config needed; Google manages nodes.
  enable_autopilot = true

  # Workload Identity is REQUIRED for the cloudsql-db auth path.
  workload_identity_config { workload_pool = "${var.project_id}.svc.id.goog" }

  # Private cluster recommended for prod; public endpoint kept for demo access.
  private_cluster_config {
    enable_private_nodes    = true
    enable_private_endpoint = false
    master_ipv4_cidr_block  = "172.16.0.0/28"
  }

  release_channel { channel = "REGULAR" }
  deletion_protection = true
}

output "endpoint" { value = google_container_cluster.main.endpoint }
output "ca_certificate" { value = google_container_cluster.main.master_auth[0].cluster_ca_certificate }
output "cluster_name" { value = google_container_cluster.main.name }
output "location" { value = google_container_cluster.main.location }
