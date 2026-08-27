resource "random_password" "db" {
  length  = 32
  special = false
}

resource "aws_db_subnet_group" "this" {
  name       = "${local.name}-db"
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_instance" "this" {
  identifier                   = "${local.name}-postgres"
  engine                       = "postgres"
  engine_version               = "16"
  instance_class               = "db.t4g.micro"
  allocated_storage            = 20
  max_allocated_storage        = 50
  storage_type                 = "gp3"
  db_name                      = "medmate"
  username                     = "medmate"
  password                     = random_password.db.result
  db_subnet_group_name         = aws_db_subnet_group.this.name
  vpc_security_group_ids       = [aws_security_group.data.id]
  storage_encrypted            = true
  kms_key_id                   = aws_kms_key.this.arn
  backup_retention_period      = 7
  backup_window                = "18:30-19:00"
  skip_final_snapshot          = false
  final_snapshot_identifier    = "${local.name}-postgres-final"
  deletion_protection          = true
  copy_tags_to_snapshot        = true
  multi_az                     = true
  publicly_accessible          = false
  apply_immediately            = true
  performance_insights_enabled = false
}

resource "aws_secretsmanager_secret" "db" {
  name       = "${local.name}/db"
  kms_key_id = aws_kms_key.this.arn
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id = aws_secretsmanager_secret.db.id
  secret_string = jsonencode({
    username = "medmate"
    password = random_password.db.result
    host     = aws_db_instance.this.address
    port     = 5432
    dbname   = "medmate"
  })
}

resource "aws_elasticache_subnet_group" "this" {
  name       = "${local.name}-redis"
  subnet_ids = aws_subnet.private[*].id
}

# Single-node Valkey — same Redis protocol, cheaper than Redis OSS on AWS.
resource "aws_elasticache_replication_group" "redis" {
  replication_group_id       = substr(replace(local.name, "_", "-"), 0, 20)
  description                = "MedMate cache"
  engine                     = "valkey"
  engine_version             = "8.0"
  node_type                  = "cache.t4g.micro"
  num_cache_clusters         = 2
  subnet_group_name          = aws_elasticache_subnet_group.this.name
  security_group_ids         = [aws_security_group.data.id]
  apply_immediately          = true
  at_rest_encryption_enabled = true
  # Existing cluster: AWS requires apply_immediately + preferred before required.
  transit_encryption_enabled = true
  transit_encryption_mode    = "preferred"
  auth_token                 = random_password.redis.result
  auth_token_update_strategy = "SET"
  automatic_failover_enabled = true
}

resource "random_password" "redis" {
  length  = 32
  special = false
}

resource "aws_s3_bucket" "uploads" {
  bucket = "${local.name}-uploads-105927215604"
}

resource "aws_s3_bucket_public_access_block" "uploads" {
  bucket                  = aws_s3_bucket.uploads.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "uploads" {
  bucket = aws_s3_bucket.uploads.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.this.arn
    }
  }
}

resource "aws_s3_bucket_versioning" "uploads" {
  bucket = aws_s3_bucket.uploads.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "uploads" {
  bucket = aws_s3_bucket.uploads.id
  rule {
    id     = "abort-incomplete"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
  rule {
    id     = "expire-noncurrent"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }
}
