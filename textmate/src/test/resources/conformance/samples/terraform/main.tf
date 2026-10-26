terraform {
  required_version = ">= 1.6"
  required_providers {
    github = {
      source  = "integrations/github"
      version = "~> 6.0"
    }
  }
}

variable "repository" {
  type        = string
  description = "The repository's name."
  default     = "Leaf"
}

# Protects the main branch.
resource "github_branch_protection" "main" {
  repository_id = data.github_repository.repo.node_id
  pattern       = "main"
  enforce_admins = false

  required_status_checks {
    strict   = true
    contexts = [for check in local.checks : "ci/${check}"]
  }
}

locals {
  checks = toset(["build", "test"])
}

output "protected" { value = length(local.checks) > 0 ? "yes" : "no" }
