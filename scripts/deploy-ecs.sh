#!/usr/bin/env bash
# Rolling ECS deploy + wait until stable (zero-downtime API via ALB minHealthy=100/max=200).
# Usage: scripts/deploy-ecs.sh staging
set -euo pipefail

ENV="${1:-staging}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STACK="${ROOT}/infra/terraform/stacks/${ENV}"
REGION="${AWS_REGION:-ap-south-1}"

need() { command -v "$1" >/dev/null || { echo "missing: $1" >&2; exit 1; }; }
need aws
need terraform

cd "$STACK"
CLUSTER="$(terraform output -raw ecs_cluster_name)"
API_SVC="$(terraform output -raw api_service_name)"
WORKER_SVC="$(terraform output -raw worker_service_name)"

aws ecs update-service --region "$REGION" --cluster "$CLUSTER" --service "$API_SVC" \
  --force-new-deployment >/dev/null
aws ecs update-service --region "$REGION" --cluster "$CLUSTER" --service "$WORKER_SVC" \
  --force-new-deployment >/dev/null
echo "Forced new deployment: ${CLUSTER}/${API_SVC} + ${WORKER_SVC}"

echo "Waiting for services-stable..."
aws ecs wait services-stable --region "$REGION" --cluster "$CLUSTER" \
  --services "$API_SVC" "$WORKER_SVC"
echo "Services stable."
