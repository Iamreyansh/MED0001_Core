terraform {
  required_version = ">= 1.10"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.80"
    }
    random = {
      source  = "hashicorp/random"
      version = ">= 3.6"
    }
    tls = {
      source  = "hashicorp/tls"
      version = ">= 4.0"
    }
  }
  # State + lockfile live only in S3 — never commit local .tfstate.
  backend "s3" {
    bucket       = "terraform-locks-105927215604"
    key          = "MED0001/staging/terraform.tfstate"
    region       = "ap-south-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = "ap-south-1"
  default_tags {
    tags = {
      Project     = "MED0001"
      Environment = "staging"
    }
  }
}

# CloudFront custom-domain certs must live in us-east-1.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"
  default_tags {
    tags = {
      Project     = "MED0001"
      Environment = "staging"
    }
  }
}

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}
