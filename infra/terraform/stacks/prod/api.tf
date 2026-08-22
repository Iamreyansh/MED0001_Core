resource "aws_ecr_repository" "api" {
  name                 = "${local.name}-api"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = true
  image_scanning_configuration { scan_on_push = true }
}

resource "aws_ecr_repository" "worker" {
  name                 = "${local.name}-worker"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = true
  image_scanning_configuration { scan_on_push = true }
}

resource "aws_ecr_lifecycle_policy" "api" {
  repository = aws_ecr_repository.api.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep last 10"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}

resource "aws_ecr_lifecycle_policy" "worker" {
  repository = aws_ecr_repository.worker.name
  policy     = aws_ecr_lifecycle_policy.api.policy
}

resource "aws_cloudwatch_log_group" "api" {
  name              = "/ecs/${local.name}-api"
  retention_in_days = 7
}

resource "aws_cloudwatch_log_group" "worker" {
  name              = "/ecs/${local.name}-worker"
  retention_in_days = 7
}

resource "aws_iam_role" "ecs_execution" {
  name = "${local.name}-ecs-execution"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ecs_execution" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "ecs_execution_secrets" {
  name = "${local.name}-ecs-execution-secrets"
  role = aws_iam_role.ecs_execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue"]
        Resource = [
          aws_secretsmanager_secret.db.arn,
          aws_secretsmanager_secret.jwt.arn,
          aws_secretsmanager_secret.mfa.arn,
          aws_secretsmanager_secret.razorpay.arn,
          aws_secretsmanager_secret.razorpayx.arn,
          aws_secretsmanager_secret.kyc.arn,
          data.aws_secretsmanager_secret.maps_geocode.arn
        ]
      },
      {
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = [aws_kms_key.this.arn]
      }
    ]
  })
}

resource "aws_iam_role" "ecs_task" {
  name = "${local.name}-ecs-task"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy" "ecs_task" {
  name = "${local.name}-ecs-task"
  role = aws_iam_role.ecs_task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes", "sqs:ChangeMessageVisibility", "sqs:SendMessage"]
        Resource = [
          aws_sqs_queue.domain_events.arn,
          aws_sqs_queue.kyc_malware_scans.arn
        ]
      },
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:GetObjectTagging"]
        Resource = ["${aws_s3_bucket.uploads.arn}/*"]
      },
      {
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = [aws_s3_bucket.uploads.arn]
      },
      {
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue"]
        Resource = [
          aws_secretsmanager_secret.db.arn,
          aws_secretsmanager_secret.jwt.arn,
          aws_secretsmanager_secret.mfa.arn,
          aws_secretsmanager_secret.razorpay.arn,
          aws_secretsmanager_secret.razorpayx.arn,
          aws_secretsmanager_secret.kyc.arn,
          data.aws_secretsmanager_secret.maps_geocode.arn
        ]
      },
      {
        Effect   = "Allow"
        Action   = ["kms:Decrypt", "kms:GenerateDataKey"]
        Resource = [aws_kms_key.this.arn]
      }
    ]
  })
}

resource "aws_s3_bucket" "alb_logs" {
  bucket = "${local.name}-alb-logs-105927215604"
}

resource "aws_s3_bucket_public_access_block" "alb_logs" {
  bucket                  = aws_s3_bucket.alb_logs.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "alb_logs" {
  bucket = aws_s3_bucket.alb_logs.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_policy" "alb_logs" {
  bucket = aws_s3_bucket.alb_logs.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AllowELBLogDelivery"
      Effect    = "Allow"
      Principal = { Service = "logdelivery.elasticloadbalancing.amazonaws.com" }
      Action    = "s3:PutObject"
      Resource  = "${aws_s3_bucket.alb_logs.arn}/*"
    }]
  })
}

resource "aws_lb" "this" {
  name               = "${local.name}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id
  idle_timeout       = 300
  access_logs {
    bucket  = aws_s3_bucket.alb_logs.bucket
    prefix  = "alb"
    enabled = true
  }
  depends_on = [aws_s3_bucket_policy.alb_logs]
}

resource "aws_wafv2_web_acl" "alb" {
  name  = "${local.name}-alb"
  scope = "REGIONAL"
  default_action {
    allow {}
  }
  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${local.name}-alb-waf"
    sampled_requests_enabled   = true
  }
  rule {
    name     = "AWS-AWSManagedRulesCommonRuleSet"
    priority = 1
    override_action {
      none {}
    }
    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-waf-common"
      sampled_requests_enabled   = true
    }
  }
}

resource "aws_wafv2_web_acl_association" "alb" {
  resource_arn = aws_lb.this.arn
  web_acl_arn  = aws_wafv2_web_acl.alb.arn
}

resource "aws_lb_target_group" "api" {
  name        = "${local.name}-api"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.this.id
  target_type = "ip"
  health_check {
    path                = "/api/v1/health"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    timeout             = 5
    interval            = 30
    matcher             = "200"
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate_validation.this.certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

resource "aws_lb_listener" "http_redirect" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_ecs_cluster" "this" {
  name = local.name
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

locals {
  api_env = [
    { name = "SPRING_PROFILES_ACTIVE", value = local.environment },
    { name = "SPRING_DATASOURCE_URL", value = "jdbc:postgresql://${aws_db_instance.this.address}:5432/medmate" },
    { name = "SPRING_DATA_REDIS_HOST", value = aws_elasticache_replication_group.redis.primary_endpoint_address },
    { name = "SPRING_DATA_REDIS_PORT", value = "6379" },
    { name = "SPRING_DATA_REDIS_SSL_ENABLED", value = "true" },
    { name = "SPRING_DATA_REDIS_PASSWORD", value = random_password.redis.result },
    { name = "MEDMATE_S3_BUCKET", value = aws_s3_bucket.uploads.bucket },
    { name = "MEDMATE_CDN_BASE_URL", value = local.cdn_base_url },
    { name = "MEDMATE_REFERRAL_JOIN_BASE_URL", value = "https://nammamedmate.com/join" },
    { name = "MEDMATE_SQS_DOMAIN_EVENTS_URL", value = aws_sqs_queue.domain_events.url },
    { name = "MEDMATE_SECRETS_DB_ARN", value = aws_secretsmanager_secret.db.arn },
    { name = "MEDMATE_SECRETS_JWT_ARN", value = aws_secretsmanager_secret.jwt.arn },
    { name = "MEDMATE_SECRETS_MFA_ARN", value = aws_secretsmanager_secret.mfa.arn },
    { name = "MEDMATE_SECRETS_RAZORPAY_ARN", value = aws_secretsmanager_secret.razorpay.arn },
    { name = "MEDMATE_SECRETS_RAZORPAYX_ARN", value = aws_secretsmanager_secret.razorpayx.arn },
    { name = "MEDMATE_SECRETS_KYC_ARN", value = aws_secretsmanager_secret.kyc.arn },
    { name = "AWS_REGION", value = data.aws_region.current.region },
    { name = "JAVA_TOOL_OPTIONS", value = "-XX:MaxRAMPercentage=75.0" }
  ]
  api_secrets = [
    { name = "SPRING_DATASOURCE_USERNAME", valueFrom = "${aws_secretsmanager_secret.db.arn}:username::" },
    { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = "${aws_secretsmanager_secret.db.arn}:password::" },
    { name = "MEDMATE_JWT_PRIVATE_KEY_PEM", valueFrom = "${aws_secretsmanager_secret.jwt.arn}:private_key_pem::" },
    { name = "MEDMATE_JWT_PUBLIC_KEY_PEM", valueFrom = "${aws_secretsmanager_secret.jwt.arn}:public_key_pem::" },
    { name = "MEDMATE_MFA_ENCRYPTION_KEY_BASE64", valueFrom = "${aws_secretsmanager_secret.mfa.arn}:encryption_key_base64::" },
    { name = "MEDMATE_PAYMENT_ENCRYPTION_KEY_BASE64", valueFrom = "${aws_secretsmanager_secret.mfa.arn}:payment_encryption_key_base64::" },
    { name = "MEDMATE_MAPS_GEOCODE_API_KEY", valueFrom = "${data.aws_secretsmanager_secret.maps_geocode.arn}:api_key::" },
    # ponytail: ECS injects these (same as JWT). BootJar relocates EPP META-INF off the app
    # classpath, so AwsSecretsEnvironmentPostProcessor never runs in the fat jar.
    { name = "MEDMATE_RAZORPAY_KEY_ID", valueFrom = "${aws_secretsmanager_secret.razorpay.arn}:key_id::" },
    { name = "MEDMATE_RAZORPAY_KEY_SECRET", valueFrom = "${aws_secretsmanager_secret.razorpay.arn}:key_secret::" },
    { name = "MEDMATE_RAZORPAY_WEBHOOK_SECRET", valueFrom = "${aws_secretsmanager_secret.razorpay.arn}:webhook_secret::" },
    { name = "MEDMATE_RAZORPAYX_WEBHOOK_SECRET", valueFrom = "${aws_secretsmanager_secret.razorpayx.arn}:webhook_secret::" },
    { name = "MEDMATE_KYC_WEBHOOK_SECRET", valueFrom = "${aws_secretsmanager_secret.kyc.arn}:webhook_secret::" }
  ]
  worker_env = [
    { name = "SPRING_PROFILES_ACTIVE", value = local.environment },
    { name = "SPRING_DATASOURCE_URL", value = "jdbc:postgresql://${aws_db_instance.this.address}:5432/medmate" },
    { name = "MEDMATE_S3_BUCKET", value = aws_s3_bucket.uploads.bucket },
    { name = "MEDMATE_SQS_DOMAIN_EVENTS_URL", value = aws_sqs_queue.domain_events.url },
    { name = "MEDMATE_SQS_KYC_MALWARE_URL", value = aws_sqs_queue.kyc_malware_scans.url },
    { name = "AWS_REGION", value = data.aws_region.current.region },
    { name = "JAVA_TOOL_OPTIONS", value = "-XX:MaxRAMPercentage=75.0" }
  ]
  worker_secrets = [
    { name = "SPRING_DATASOURCE_USERNAME", valueFrom = "${aws_secretsmanager_secret.db.arn}:username::" },
    { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = "${aws_secretsmanager_secret.db.arn}:password::" }
  ]
}

resource "aws_ecs_task_definition" "api" {
  family                   = "${local.name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 1024
  memory                   = 2048
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }
  container_definitions = jsonencode([{
    name         = "api"
    image        = local.api_image
    essential    = true
    portMappings = [{ containerPort = 8080, protocol = "tcp" }]
    environment  = local.api_env
    secrets      = local.api_secrets
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.api.name
        "awslogs-region"        = data.aws_region.current.region
        "awslogs-stream-prefix" = "api"
      }
    }
  }])
}

resource "aws_ecs_task_definition" "worker" {
  family                   = "${local.name}-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 1024
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }
  container_definitions = jsonencode([{
    name        = "worker"
    image       = local.worker_image
    essential   = true
    environment = local.worker_env
    secrets     = local.worker_secrets
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.worker.name
        "awslogs-region"        = data.aws_region.current.region
        "awslogs-stream-prefix" = "worker"
      }
    }
  }])
}

resource "aws_ecs_service" "api" {
  name                               = "${local.name}-api"
  cluster                            = aws_ecs_cluster.this.id
  task_definition                    = aws_ecs_task_definition.api.arn
  desired_count                      = 2
  launch_type                        = "FARGATE"
  health_check_grace_period_seconds  = 120
  deployment_maximum_percent         = 200
  deployment_minimum_healthy_percent = 100
  # ponytail: public IP skips NAT Gateway (~$37/mo). SGs still lock ingress to ALB-only.
  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.ecs.id]
    assign_public_ip = true
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = 8080
  }
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  depends_on = [aws_lb_listener.https]
}

resource "aws_ecs_service" "worker" {
  name                               = "${local.name}-worker"
  cluster                            = aws_ecs_cluster.this.id
  task_definition                    = aws_ecs_task_definition.worker.arn
  desired_count                      = 2
  launch_type                        = "FARGATE"
  deployment_maximum_percent         = 200
  deployment_minimum_healthy_percent = 100
  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.ecs.id]
    assign_public_ip = true
  }
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
}

resource "aws_route53_record" "api" {
  zone_id = local.hosted_zone_id
  name    = local.domain_name
  type    = "A"
  alias {
    name                   = aws_lb.this.dns_name
    zone_id                = aws_lb.this.zone_id
    evaluate_target_health = true
  }
}
