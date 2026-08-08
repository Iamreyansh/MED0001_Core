data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

data "aws_iam_role" "gha" {
  name = local.deploy_role
}

# Shared GitHub Actions CI storage (Gradle cache, boot jars, reports, tf plans).
# Account-scoped; not the app uploads bucket.
resource "aws_s3_bucket" "gha_ci" {
  bucket = local.ci_bucket
}

resource "aws_s3_bucket_public_access_block" "gha_ci" {
  bucket                  = aws_s3_bucket.gha_ci.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "gha_ci" {
  bucket = aws_s3_bucket.gha_ci.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "gha_ci" {
  bucket = aws_s3_bucket.gha_ci.id

  rule {
    id     = "abort-incomplete"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  rule {
    id     = "expire-gradle-cache"
    status = "Enabled"
    filter {
      prefix = "gradle-cache/"
    }
    expiration {
      days = 14
    }
  }

  rule {
    id     = "expire-artifacts"
    status = "Enabled"
    filter {
      prefix = "artifacts/"
    }
    expiration {
      days = 3
    }
  }

  rule {
    id     = "expire-reports"
    status = "Enabled"
    filter {
      prefix = "reports/"
    }
    expiration {
      days = 7
    }
  }

  rule {
    id     = "expire-tfplans"
    status = "Enabled"
    filter {
      prefix = "tfplans/"
    }
    expiration {
      days = 7
    }
  }
}

resource "aws_iam_role_policy" "gha_deploy" {
  name = "${local.name}-gha-deploy"
  role = data.aws_iam_role.gha.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:ListBucket",
          "s3:GetBucketVersioning", "s3:GetEncryptionConfiguration"
        ]
        Resource = [
          "arn:aws:s3:::${local.state_bucket}",
          "arn:aws:s3:::${local.state_bucket}/*"
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:ListBucket"
        ]
        Resource = [
          aws_s3_bucket.gha_ci.arn,
          "${aws_s3_bucket.gha_ci.arn}/*"
        ]
      },
      {
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = ["*"]
      },
      {
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer",
          "ecr:BatchGetImage", "ecr:PutImage", "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart", "ecr:CompleteLayerUpload", "ecr:DescribeRepositories"
        ]
        Resource = [aws_ecr_repository.api.arn, aws_ecr_repository.worker.arn]
      },
      {
        Effect = "Allow"
        Action = [
          "ecs:UpdateService", "ecs:DescribeServices", "ecs:DescribeTaskDefinition",
          "ecs:RegisterTaskDefinition", "ecs:ListTasks", "ecs:DescribeTasks",
          "ecs:DescribeClusters"
        ]
        Resource = ["*"]
      },
      {
        Effect   = "Allow"
        Action   = ["iam:PassRole"]
        Resource = ["arn:aws:iam::${local.account_id}:role/${local.name}-ecs-*"]
      }
    ]
  })
}
