# Cloud SQL for PostgreSQL — one instance hosting the three service DBs
# (userdb, orderdb, paymentdb), matching database-per-service ownership.
variable "project_id" { type = string }
variable "region" { type = string }
variable "instance_name" { type = string }
variable "tier" { type = string }
variable "disk_size" { type = number }
variable "databases" { type = list(string) }
variable "backup_enabled" { type = bool }
variable "deletion_protect" { type = bool }
variable "private_network" { type = string }   # VPC self-link for private services access

resource "google_sql_database_instance" "main" {
  name             = var.instance_name
  database_version = "POSTGRES_16"
  region           = var.region
  project          = var.project_id

  settings {
    tier              = var.tier
    disk_size         = var.disk_size
    disk_autoresize   = true
    deletion_protection_enabled = var.deletion_protect

    ip_configuration {
      ipv4_enabled    = false          # private IP only
      private_network = var.private_network
    }

    backup_configuration {
      enabled                        = var.backup_enabled
      point_in_time_recovery_enabled = var.backup_enabled
      start_time                     = "03:00"
    }

    insights_config { query_insights_enabled = true }
  }

  depends_on = [google_service_networking_connection.private_vpc_connection]
}

# Private services access — Cloud SQL reaches into the VPC without public IPs.
resource "google_compute_global_address" "private_ip_alloc" {
  name          = "${var.instance_name}-ip"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = var.private_network
  project       = var.project_id
}

resource "google_service_networking_connection" "private_vpc_connection" {
  network                 = var.private_network
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_ip_alloc.name]
}

resource "google_sql_database" "dbs" {
  for_each = toset(var.databases)
  name     = each.value
  instance = google_sql_database_instance.main.name
  project  = var.project_id
}

# Per-service DB users — least privilege, one role per service.
resource "google_sql_user" "svc_users" {
  for_each = toset(["user_svc", "order_svc", "payment_svc"])
  name     = each.value
  instance = google_sql_database_instance.main.name
  project  = var.project_id
  password = "CHANGE_ME_${each.value}"   # real passwords via TFC sensitive vars
}

# NOTE: grant each user ALL on its own database only — do this in a
# postgresql provider or gcloud step; Terraform google provider doesn't
# manage GRANTs per-database cleanly.

output "instance_name" { value = google_sql_database_instance.main.name }
output "connection_name" { value = google_sql_database_instance.main.connection_name }
output "private_ip" { value = google_sql_database_instance.main.private_ip_address }
