terraform {
  required_version = ">= 1.10"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.80"
    }
  }
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

locals {
  name           = "med0001-staging"
  hosted_zone_id = "Z092619528F68GS3HR1ZE"
  domain_name    = "staging-core.api.nammamedmate.com"
  azs            = ["ap-south-1a", "ap-south-1b"]
}

resource "aws_kms_key" "this" {
  description             = "MED0001 staging"
  deletion_window_in_days = 7
  enable_key_rotation     = true
}

resource "aws_s3_bucket" "artifacts" {
  bucket = "med0001-staging-artifacts-105927215604"
}

resource "aws_s3_bucket_versioning" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.this.arn
    }
  }
}

resource "aws_s3_bucket_public_access_block" "artifacts" {
  bucket                  = aws_s3_bucket.artifacts.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

module "network" {
  source = "../../modules/network"
  name   = local.name
  cidr   = "10.20.0.0/16"
  azs    = local.azs
}

module "data" {
  source            = "../../modules/data"
  name              = local.name
  subnet_ids        = module.network.private_subnet_ids
  security_group_id = module.network.data_security_group_id
  kms_key_arn       = aws_kms_key.this.arn
}

module "messaging" {
  source = "../../modules/messaging"
  name   = local.name
}

module "secrets" {
  source      = "../../modules/secrets"
  name        = local.name
  kms_key_arn = aws_kms_key.this.arn
}

module "api" {
  source                  = "../../modules/api"
  name                    = local.name
  environment             = "staging"
  subnet_ids              = module.network.private_subnet_ids
  security_group_ids      = [module.network.lambda_security_group_id]
  artifacts_bucket        = aws_s3_bucket.artifacts.bucket
  api_s3_key              = "lambda/api.zip"
  worker_s3_key           = "lambda/worker.zip"
  domain_events_queue_arn = module.messaging.domain_events_queue_arn
  domain_events_queue_url = module.messaging.domain_events_queue_url
  db_secret_arn           = module.data.db_secret_arn
  jwt_secret_arn          = module.secrets.jwt_secret_arn
  uploads_bucket          = module.data.uploads_bucket
  db_proxy_endpoint       = module.data.db_proxy_endpoint
  redis_endpoint          = module.data.redis_primary_endpoint
  kms_key_arn             = aws_kms_key.this.arn
  provisioned_concurrency = 0
}

module "edge" {
  source               = "../../modules/edge"
  name                 = local.name
  domain_name          = local.domain_name
  hosted_zone_id       = local.hosted_zone_id
  lambda_invoke_arn    = module.api.api_invoke_arn
  lambda_function_name = module.api.api_function_name
}

module "observability" {
  source               = "../../modules/observability"
  name                 = local.name
  api_function_name    = module.api.api_function_name
  worker_function_name = module.api.worker_function_name
  dlq_arn              = module.messaging.dlq_arn
}

# Requires scripts/bootstrap-github-aws.sh to have been run first (GitHub OIDC provider
# + med0001-gha-deploy role); the data sources below resolve pre-existing resources.
module "ci" {
  source           = "../../modules/ci"
  name             = local.name
  github_org       = "Iamreyansh"
  github_repo      = "MED0001_Core"
  artifacts_bucket = aws_s3_bucket.artifacts.bucket
  state_bucket     = "terraform-locks-105927215604"
}

output "deploy_role_arn" { value = module.ci.deploy_role_arn }
output "api_domain" { value = module.edge.custom_domain }
output "artifacts_bucket" { value = aws_s3_bucket.artifacts.bucket }
