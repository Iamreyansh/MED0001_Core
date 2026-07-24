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
    key          = "MED0001/prod/terraform.tfstate"
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
      Environment = "prod"
    }
  }
}

locals {
  name           = "med0001-prod"
  hosted_zone_id = "Z092619528F68GS3HR1ZE"
  domain_name    = "core.api.nammamedmate.com"
  azs            = ["ap-south-1a", "ap-south-1b"]
}

resource "aws_kms_key" "this" {
  description             = "MED0001 prod"
  deletion_window_in_days = 30
  enable_key_rotation     = true
}

resource "aws_s3_bucket" "artifacts" {
  bucket = "med0001-prod-artifacts-105927215604"
}

resource "aws_s3_bucket_versioning" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id
  versioning_configuration { status = "Enabled" }
}

module "network" {
  source = "../../modules/network"
  name   = local.name
  cidr   = "10.30.0.0/16"
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
  source = "../../modules/secrets"
  name   = local.name
}

module "api" {
  source                  = "../../modules/api"
  name                    = local.name
  environment             = "prod"
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
  provisioned_concurrency = 1
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

# GitHub OIDC deploy role is created in the staging stack (account-unique provider URL).

resource "aws_scheduler_schedule" "weekly_settlement_ist" {
  name       = "${local.name}-weekly-settlement-ist"
  group_name = "default"
  flexible_time_window { mode = "OFF" }
  schedule_expression          = "cron(0 6 ? * MON *)"
  schedule_expression_timezone = "Asia/Kolkata"
  state                        = "DISABLED"
  target {
    arn      = module.messaging.domain_events_queue_arn
    role_arn = aws_iam_role.scheduler.arn
    input    = jsonencode({ type = "finance.settlement.weekly" })
  }
}

resource "aws_iam_role" "scheduler" {
  name = "${local.name}-scheduler"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "scheduler.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "scheduler" {
  name = "${local.name}-scheduler"
  role = aws_iam_role.scheduler.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["sqs:SendMessage"]
      Resource = [module.messaging.domain_events_queue_arn]
    }]
  })
}

output "api_domain" { value = module.edge.custom_domain }
output "artifacts_bucket" { value = aws_s3_bucket.artifacts.bucket }
