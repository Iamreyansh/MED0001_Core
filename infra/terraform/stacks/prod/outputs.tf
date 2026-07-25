output "deploy_role_arn" { value = data.aws_iam_role.gha.arn }
output "api_domain" { value = local.domain_name }
output "api_ecr_url" { value = aws_ecr_repository.api.repository_url }
output "worker_ecr_url" { value = aws_ecr_repository.worker.repository_url }
output "ecs_cluster_name" { value = aws_ecs_cluster.this.name }
output "api_service_name" { value = aws_ecs_service.api.name }
output "worker_service_name" { value = aws_ecs_service.worker.name }
