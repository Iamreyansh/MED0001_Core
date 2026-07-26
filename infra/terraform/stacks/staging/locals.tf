variable "image_tag" {
  type        = string
  description = "ECR image tag for api/worker (CI sets to GITHUB_SHA)"
  default     = "staging"
}

locals {
  name            = "med0001-staging"
  environment     = "staging"
  hosted_zone_id  = "Z092619528F68GS3HR1ZE"
  domain_name     = "core.api.staging.nammamedmate.com"
  cdn_domain_name = "cdn.staging.nammamedmate.com"
  cdn_base_url    = "https://${local.cdn_domain_name}"
  azs             = ["ap-south-1a", "ap-south-1b"]
  cidr            = "10.20.0.0/16"
  account_id      = data.aws_caller_identity.current.account_id
  api_image       = "${local.account_id}.dkr.ecr.ap-south-1.amazonaws.com/${local.name}-api:${var.image_tag}"
  worker_image    = "${local.account_id}.dkr.ecr.ap-south-1.amazonaws.com/${local.name}-worker:${var.image_tag}"
  state_bucket    = "terraform-locks-105927215604"
  github_org      = "Iamreyansh"
  github_repo     = "MED0001_Core"
  deploy_role     = "med0001-gha-deploy"
  # Public media only — private keys (kyc/, prescriptions/, …) stay presigned-S3.
  cdn_public_prefixes = ["avatars/", "products/", "pharmacies/", "doctors/", "riders/", "banners/"]
}

resource "aws_kms_key" "this" {
  description             = "MED0001 staging"
  deletion_window_in_days = 7
  enable_key_rotation     = true
}
