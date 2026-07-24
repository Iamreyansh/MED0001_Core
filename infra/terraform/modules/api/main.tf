terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.80"
    }
  }
}

variable "name" { type = string }
variable "subnet_ids" { type = list(string) }
variable "security_group_ids" { type = list(string) }
variable "api_s3_key" { type = string }
variable "worker_s3_key" { type = string }
variable "artifacts_bucket" { type = string }
variable "domain_events_queue_arn" { type = string }
variable "domain_events_queue_url" { type = string }
variable "db_secret_arn" { type = string }
variable "jwt_secret_arn" { type = string }
variable "uploads_bucket" { type = string }
variable "db_proxy_endpoint" { type = string }
variable "redis_endpoint" { type = string }
variable "environment" { type = string }
variable "provisioned_concurrency" {
  type    = number
  default = 0
}

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  # AWS Lambda Web Adapter layer (arm64) — public AWS account
  lwa_layer_arn = "arn:aws:lambda:ap-south-1:753240598075:layer:LambdaAdapterLayerArm64:25"
}

resource "aws_iam_role" "lambda" {
  name = "${var.name}-lambda"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "basic" {
  role       = aws_iam_role.lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "vpc" {
  role       = aws_iam_role.lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

resource "aws_iam_role_policy" "app" {
  name = "${var.name}-lambda-app"
  role = aws_iam_role.lambda.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = [var.db_secret_arn, var.jwt_secret_arn]
      },
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject"]
        Resource = ["arn:aws:s3:::${var.uploads_bucket}/*"]
      },
      {
        Effect   = "Allow"
        Action   = ["sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
        Resource = [var.domain_events_queue_arn]
      }
    ]
  })
}

resource "aws_lambda_function" "api" {
  function_name = "${var.name}-api"
  role          = aws_iam_role.lambda.arn
  handler       = "com.nammamedmate.api.ApiApplication::main"
  runtime       = "java21"
  architectures = ["arm64"]
  memory_size   = 1024
  timeout       = 29
  s3_bucket     = var.artifacts_bucket
  s3_key        = var.api_s3_key
  layers        = [local.lwa_layer_arn]
  publish       = true

  environment {
    variables = {
      AWS_LAMBDA_EXEC_WRAPPER       = "/opt/bootstrap"
      AWS_LWA_ASYNC_INIT            = "true"
      AWS_LWA_PORT                  = "8080"
      PORT                          = "8080"
      MAIN_CLASS                    = "com.nammamedmate.api.ApiApplication"
      JAVA_TOOL_OPTIONS             = "-XX:+TieredCompilation -XX:TieredStopAtLevel=1"
      SPRING_PROFILES_ACTIVE        = var.environment
      SPRING_DATASOURCE_URL         = "jdbc:postgresql://${var.db_proxy_endpoint}:5432/medmate"
      SPRING_DATA_REDIS_HOST        = var.redis_endpoint
      MEDMATE_S3_BUCKET             = var.uploads_bucket
      MEDMATE_SQS_DOMAIN_EVENTS_URL = var.domain_events_queue_url
      MEDMATE_SECRETS_DB_ARN        = var.db_secret_arn
      MEDMATE_SECRETS_JWT_ARN       = var.jwt_secret_arn
    }
  }

  vpc_config {
    subnet_ids         = var.subnet_ids
    security_group_ids = var.security_group_ids
  }

  snap_start {
    apply_on = "PublishedVersions"
  }
}

resource "aws_lambda_alias" "api_live" {
  name             = "live"
  function_name    = aws_lambda_function.api.function_name
  function_version = aws_lambda_function.api.version
}

resource "aws_lambda_provisioned_concurrency_config" "api" {
  count                             = var.provisioned_concurrency > 0 ? 1 : 0
  function_name                     = aws_lambda_function.api.function_name
  qualifier                         = aws_lambda_alias.api_live.name
  provisioned_concurrent_executions = var.provisioned_concurrency
}

resource "aws_lambda_function" "worker" {
  function_name = "${var.name}-worker"
  role          = aws_iam_role.lambda.arn
  handler       = "com.nammamedmate.worker.WorkerApplication::main"
  runtime       = "java21"
  architectures = ["arm64"]
  memory_size   = 1024
  timeout       = 60
  s3_bucket     = var.artifacts_bucket
  s3_key        = var.worker_s3_key
  layers        = [local.lwa_layer_arn]
  publish       = true

  environment {
    variables = {
      AWS_LAMBDA_EXEC_WRAPPER       = "/opt/bootstrap"
      AWS_LWA_PORT                  = "8080"
      PORT                          = "8080"
      MAIN_CLASS                    = "com.nammamedmate.worker.WorkerApplication"
      JAVA_TOOL_OPTIONS             = "-XX:+TieredCompilation -XX:TieredStopAtLevel=1"
      SPRING_PROFILES_ACTIVE        = var.environment
      MEDMATE_SQS_DOMAIN_EVENTS_URL = var.domain_events_queue_url
    }
  }

  vpc_config {
    subnet_ids         = var.subnet_ids
    security_group_ids = var.security_group_ids
  }

  snap_start {
    apply_on = "PublishedVersions"
  }
}

resource "aws_lambda_alias" "worker_live" {
  name             = "live"
  function_name    = aws_lambda_function.worker.function_name
  function_version = aws_lambda_function.worker.version
}

resource "aws_lambda_event_source_mapping" "worker_sqs" {
  event_source_arn = var.domain_events_queue_arn
  function_name    = aws_lambda_alias.worker_live.arn
  batch_size       = 5
  enabled          = true
}

output "api_function_name" { value = aws_lambda_function.api.function_name }
output "api_alias_arn" { value = aws_lambda_alias.api_live.arn }
output "api_invoke_arn" { value = aws_lambda_alias.api_live.invoke_arn }
output "worker_function_name" { value = aws_lambda_function.worker.function_name }
