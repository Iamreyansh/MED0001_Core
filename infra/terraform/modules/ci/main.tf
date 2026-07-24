terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.80"
    }
  }
}

variable "name" { type = string }
variable "github_org" { type = string }
variable "github_repo" { type = string }
variable "artifacts_bucket" { type = string }
variable "state_bucket" { type = string }
variable "deploy_role_name" {
  type    = string
  default = "med0001-gha-deploy"
}

data "aws_caller_identity" "current" {}
data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

# Prefer the bootstrap CLI role; attach extra inline policy for buckets if needed.
data "aws_iam_role" "gha" {
  name = var.deploy_role_name
}

resource "aws_iam_role_policy" "gha_buckets" {
  name = "${var.name}-gha-buckets"
  role = data.aws_iam_role.gha.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = ["s3:*"]
      Resource = [
        "arn:aws:s3:::${var.artifacts_bucket}",
        "arn:aws:s3:::${var.artifacts_bucket}/*",
        "arn:aws:s3:::${var.state_bucket}",
        "arn:aws:s3:::${var.state_bucket}/*"
      ]
    }]
  })
}

output "deploy_role_arn" { value = data.aws_iam_role.gha.arn }
output "oidc_provider_arn" { value = data.aws_iam_openid_connect_provider.github.arn }
