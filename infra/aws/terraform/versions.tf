terraform {
  required_version = ">= 1.6"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 5.70" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }
  # Remote state with locking (create the bucket/table once, then `terraform init -backend-config=...`)
  backend "s3" {}
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      Project     = "gymops"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
