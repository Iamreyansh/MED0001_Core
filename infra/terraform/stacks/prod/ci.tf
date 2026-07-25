data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

data "aws_iam_role" "gha" {
  name = local.deploy_role
}

# Staging ECR — pull digests for bit-identical promote into prod.
data "aws_ecr_repository" "staging_api" {
  name = "med0001-staging-api"
}

data "aws_ecr_repository" "staging_worker" {
  name = "med0001-staging-worker"
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
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = ["*"]
      },
      {
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer",
          "ecr:BatchGetImage", "ecr:DescribeRepositories"
        ]
        Resource = [
          data.aws_ecr_repository.staging_api.arn,
          data.aws_ecr_repository.staging_worker.arn
        ]
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
