# Auto-generated: flatten module.* addresses to root without destroy.
moved {
  from = module.api.aws_cloudwatch_log_group.api
  to   = aws_cloudwatch_log_group.api
}

moved {
  from = module.api.aws_cloudwatch_log_group.worker
  to   = aws_cloudwatch_log_group.worker
}

moved {
  from = module.api.aws_ecr_lifecycle_policy.api
  to   = aws_ecr_lifecycle_policy.api
}

moved {
  from = module.api.aws_ecr_lifecycle_policy.worker
  to   = aws_ecr_lifecycle_policy.worker
}

moved {
  from = module.api.aws_ecr_repository.api
  to   = aws_ecr_repository.api
}

moved {
  from = module.api.aws_ecr_repository.worker
  to   = aws_ecr_repository.worker
}

moved {
  from = module.api.aws_ecs_cluster.this
  to   = aws_ecs_cluster.this
}

moved {
  from = module.api.aws_ecs_service.api
  to   = aws_ecs_service.api
}

moved {
  from = module.api.aws_ecs_service.worker
  to   = aws_ecs_service.worker
}

moved {
  from = module.api.aws_ecs_task_definition.api
  to   = aws_ecs_task_definition.api
}

moved {
  from = module.api.aws_ecs_task_definition.worker
  to   = aws_ecs_task_definition.worker
}

moved {
  from = module.api.aws_iam_role.ecs_execution
  to   = aws_iam_role.ecs_execution
}

moved {
  from = module.api.aws_iam_role.ecs_task
  to   = aws_iam_role.ecs_task
}

moved {
  from = module.api.aws_iam_role_policy.ecs_execution_secrets
  to   = aws_iam_role_policy.ecs_execution_secrets
}

moved {
  from = module.api.aws_iam_role_policy.ecs_task
  to   = aws_iam_role_policy.ecs_task
}

moved {
  from = module.api.aws_iam_role_policy_attachment.ecs_execution
  to   = aws_iam_role_policy_attachment.ecs_execution
}

moved {
  from = module.api.aws_lb.this
  to   = aws_lb.this
}

moved {
  from = module.api.aws_lb_listener.http_redirect
  to   = aws_lb_listener.http_redirect
}

moved {
  from = module.api.aws_lb_listener.https
  to   = aws_lb_listener.https
}

moved {
  from = module.api.aws_lb_target_group.api
  to   = aws_lb_target_group.api
}

moved {
  from = module.ci.aws_iam_role_policy.gha_deploy
  to   = aws_iam_role_policy.gha_deploy
}

moved {
  from = module.data.aws_db_instance.this
  to   = aws_db_instance.this
}

moved {
  from = module.data.aws_db_subnet_group.this
  to   = aws_db_subnet_group.this
}

moved {
  from = module.data.aws_elasticache_replication_group.redis
  to   = aws_elasticache_replication_group.redis
}

moved {
  from = module.data.aws_elasticache_subnet_group.this
  to   = aws_elasticache_subnet_group.this
}

moved {
  from = module.data.aws_s3_bucket.uploads
  to   = aws_s3_bucket.uploads
}

moved {
  from = module.data.aws_s3_bucket_lifecycle_configuration.uploads
  to   = aws_s3_bucket_lifecycle_configuration.uploads
}

moved {
  from = module.data.aws_s3_bucket_public_access_block.uploads
  to   = aws_s3_bucket_public_access_block.uploads
}

moved {
  from = module.data.aws_s3_bucket_server_side_encryption_configuration.uploads
  to   = aws_s3_bucket_server_side_encryption_configuration.uploads
}

moved {
  from = module.data.aws_secretsmanager_secret.db
  to   = aws_secretsmanager_secret.db
}

moved {
  from = module.data.aws_secretsmanager_secret_version.db
  to   = aws_secretsmanager_secret_version.db
}

moved {
  from = module.data.random_password.db
  to   = random_password.db
}

moved {
  from = module.edge.aws_acm_certificate.this
  to   = aws_acm_certificate.this
}

moved {
  from = module.edge.aws_acm_certificate_validation.this
  to   = aws_acm_certificate_validation.this
}

moved {
  from = module.edge.aws_route53_record.cert_validation["staging-core.api.nammamedmate.com"]
  to   = aws_route53_record.cert_validation["staging-core.api.nammamedmate.com"]
}

moved {
  from = module.messaging.aws_scheduler_schedule_group.this
  to   = aws_scheduler_schedule_group.this
}

moved {
  from = module.messaging.aws_sqs_queue.dlq
  to   = aws_sqs_queue.dlq
}

moved {
  from = module.messaging.aws_sqs_queue.domain_events
  to   = aws_sqs_queue.domain_events
}

moved {
  from = module.network.aws_internet_gateway.this
  to   = aws_internet_gateway.this
}

moved {
  from = module.network.aws_route_table.private
  to   = aws_route_table.private
}

moved {
  from = module.network.aws_route_table.public
  to   = aws_route_table.public
}

moved {
  from = module.network.aws_route_table_association.private[0]
  to   = aws_route_table_association.private[0]
}

moved {
  from = module.network.aws_route_table_association.private[1]
  to   = aws_route_table_association.private[1]
}

moved {
  from = module.network.aws_route_table_association.public[0]
  to   = aws_route_table_association.public[0]
}

moved {
  from = module.network.aws_route_table_association.public[1]
  to   = aws_route_table_association.public[1]
}

moved {
  from = module.network.aws_security_group.alb
  to   = aws_security_group.alb
}

moved {
  from = module.network.aws_security_group.data
  to   = aws_security_group.data
}

moved {
  from = module.network.aws_security_group.ecs
  to   = aws_security_group.ecs
}

moved {
  from = module.network.aws_subnet.private[0]
  to   = aws_subnet.private[0]
}

moved {
  from = module.network.aws_subnet.private[1]
  to   = aws_subnet.private[1]
}

moved {
  from = module.network.aws_subnet.public[0]
  to   = aws_subnet.public[0]
}

moved {
  from = module.network.aws_subnet.public[1]
  to   = aws_subnet.public[1]
}

moved {
  from = module.network.aws_vpc.this
  to   = aws_vpc.this
}

moved {
  from = module.network.aws_vpc_endpoint.s3
  to   = aws_vpc_endpoint.s3
}

moved {
  from = module.observability.aws_cloudwatch_metric_alarm.alb_5xx
  to   = aws_cloudwatch_metric_alarm.alb_5xx
}

moved {
  from = module.observability.aws_cloudwatch_metric_alarm.dlq_depth
  to   = aws_cloudwatch_metric_alarm.dlq_depth
}

moved {
  from = module.observability.aws_cloudwatch_metric_alarm.rds_cpu
  to   = aws_cloudwatch_metric_alarm.rds_cpu
}

moved {
  from = module.observability.aws_sns_topic.alarms
  to   = aws_sns_topic.alarms
}

moved {
  from = module.secrets.aws_secretsmanager_secret.jwt
  to   = aws_secretsmanager_secret.jwt
}

moved {
  from = module.secrets.aws_secretsmanager_secret_version.jwt
  to   = aws_secretsmanager_secret_version.jwt
}

moved {
  from = module.secrets.tls_private_key.jwt
  to   = tls_private_key.jwt
}

