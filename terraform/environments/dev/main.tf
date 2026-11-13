# Dev environment root — smallest footprint, cheapest tier.
# TFE workspace "commercehub-dev" points VCS at environments/dev/;
# every commit to the dev branch triggers plan, merge triggers apply.

terraform {
  required_version = ">= 1.6"

  required_providers {
    google = { source = "hashicorp/google", version = "~> 6.0" }
    kubernetes = { source = "hashicorp/kubernetes", version = "~> 2.30" }
  }

  # Terraform Cloud remote backend — VCS-driven plan/apply.
  # Workspace settings in TFC: VCS repo + branch (e.g. main -> auto plan,
  # merge -> auto apply with speculative plans on PRs).
  backend "remote" {
    organization = "commercehub"
    workspaces { name = "commercehub-dev" }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

# K8s provider authenticates via GKE cluster outputs (gke module).
provider "kubernetes" {
  host                   = "https://${module.gke.endpoint}"
  token                  = data.google_client_config.current.access_token
  cluster_ca_certificate = base64decode(module.gke.ca_certificate)
}

data "google_client_config" "current" {}
