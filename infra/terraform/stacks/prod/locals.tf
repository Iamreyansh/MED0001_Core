variable "image_tag" {
  type        = string
  description = "ECR image tag for api/worker (CI sets to release-* tag)"
  default     = "prod"
}

locals {
  name           = "med0001-prod"
  environment    = "prod"
  hosted_zone_id = "Z092619528F68GS3HR1ZE"
  domain_name    = "core.api.nammamedmate.com"
  azs            = ["ap-south-1a", "ap-south-1b"]
  cidr           = "10.30.0.0/16"
  account_id     = data.aws_caller_identity.current.account_id
  api_image      = "${local.account_id}.dkr.ecr.ap-south-1.amazonaws.com/${local.name}-api:${var.image_tag}"
  worker_image   = "${local.account_id}.dkr.ecr.ap-south-1.amazonaws.com/${local.name}-worker:${var.image_tag}"
  state_bucket   = "terraform-locks-105927215604"
  github_org     = "Iamreyansh"
  github_repo    = "MED0001_Core"
  deploy_role    = "med0001-gha-deploy"
}

resource "aws_kms_key" "this" {
  description             = "MED0001 prod"
  deletion_window_in_days = 7
  enable_key_rotation     = true
}
