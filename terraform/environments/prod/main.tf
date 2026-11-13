# Prod root — same modules, production inputs. Separate TFC workspace
# "commercehub-prod" with its own VCS branch/environment scope.
terraform {
  required_version = ">= 1.6"
  required_providers {
    google    = { source = "hashicorp/google", version = "~> 6.0" }
    kubernetes = { source = "hashicorp/kubernetes", version = "~> 2.30" }
  }
  backend "remote" {
    organization = "commercehub"
    workspaces { name = "commercehub-prod" }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

data "google_client_config" "current" {}

provider "kubernetes" {
  host                    = "https://${module.gke.endpoint}"
  token                   = data.google_client_config.current.access_token
  cluster_ca_certificate  = base64decode(module.gke.ca_certificate)
}
