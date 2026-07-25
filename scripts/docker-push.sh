#!/usr/bin/env bash
# Build boot jars and push ARM64 images to ECR.
# Usage: scripts/docker-push.sh staging
set -euo pipefail

ENV="${1:-staging}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STACK="${ROOT}/infra/terraform/stacks/${ENV}"
REGION="${AWS_REGION:-ap-south-1}"
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
TAG="${IMAGE_TAG:-${ENV}}"

need() { command -v "$1" >/dev/null || { echo "missing: $1" >&2; exit 1; }; }
need aws
need docker
need terraform

cd "$ROOT"
./gradlew :apps:api:bootJar :apps:worker:bootJar -x test ${GRADLE_FLAGS:---no-daemon}

cd "$STACK"
API_REPO="$(terraform output -raw api_ecr_url)"
WORKER_REPO="$(terraform output -raw worker_ecr_url)"

aws ecr get-login-password --region "$REGION" \
  | docker login --username AWS --password-stdin "${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"

docker buildx build --platform linux/arm64 -f "${ROOT}/apps/api/Dockerfile" -t "${API_REPO}:${TAG}" --push "${ROOT}"
docker buildx build --platform linux/arm64 -f "${ROOT}/apps/worker/Dockerfile" -t "${WORKER_REPO}:${TAG}" --push "${ROOT}"

echo "Pushed ${API_REPO}:${TAG} and ${WORKER_REPO}:${TAG}"
