variable "project_id" { type = string }
variable "region" { type = string default = "us-central1" }
variable "environment" { type = string default = "dev" }
variable "db_tier" { type = string default = "db-f1-micro" }
variable "db_disk_gb" { type = number default = 10 }
variable "db_backup_enabled" { type = bool default = false }
variable "gke_node_count" { type = number default = 1 }
variable "gke_machine_type" { type = string default = "e2-medium" }

# Dev tfvars example — real values go in TFC workspace variables, not git.
locals {
  db_name_prefix = "commercehub-${var.environment}"
  databases      = ["userdb", "orderdb", "paymentdb"]
}

module "gke" {
  source       = "../../modules/gke"
  project_id   = var.project_id
  region       = var.region
  cluster_name = "${local.db_name_prefix}-gke"
  node_count   = var.gke_node_count
  machine_type = var.gke_machine_type
}

module "app_db" {
  source           = "../../modules/cloudsql-db"
  project_id       = var.project_id
  region           = var.region
  instance_name    = "${local.db_name_prefix}-pg"
  tier             = var.db_tier
  disk_size        = var.db_disk_gb
  databases        = local.databases
  backup_enabled   = var.db_backup_enabled
  private_network  = "projects/${var.project_id}/global/networks/default"
  deletion_protect = var.environment == "prod"
}

module "workload_identity" {
  source          = "../../modules/workload-identity"
  project_id      = var.project_id
  cluster_name    = module.gke.cluster_name
  namespace       = "shopsphere"
  ksa_name        = "commercehub-app"
  gsa_account_id  = "commercehub-${var.environment}-app"
  sql_instance    = module.app_db.instance_name
}

output "db_private_ip" { value = module.app_db.private_ip }
output "db_connection_name" { value = module.app_db.connection_name }
output "gke_endpoint" { value = module.gke.endpoint }
