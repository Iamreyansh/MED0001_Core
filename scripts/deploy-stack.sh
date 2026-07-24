#!/usr/bin/env bash
# Deploy Lambda zips + terraform apply for staging|prod. Invoked via: make deploy ENV=...
# Traffic uses alias "live" (not $LATEST) — publish + alias update after apply.
set -euo pipefail

ENV="${1:?ENV required (staging|prod)}"
case "$ENV" in
  staging|prod) ;;
  *) echo "ENV must be staging or prod"; exit 1 ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STACK="$ROOT/infra/terraform/stacks/$ENV"
PUBLISH="$ROOT/scripts/ci/publish-lambda.sh"
API_FN="med0001-${ENV}-api"
WORKER_FN="med0001-${ENV}-worker"
DEFAULT_BUCKET="med0001-${ENV}-artifacts-105927215604"
API_ZIP="$ROOT/infra/lambda/api.zip"
WORKER_ZIP="$ROOT/infra/lambda/worker.zip"

for f in "$API_ZIP" "$WORKER_ZIP" "$PUBLISH"; do
  if [ ! -f "$f" ]; then
    echo "Missing required file: $f" >&2
    exit 1
  fi
done
chmod +x "$PUBLISH"

cd "$STACK"
terraform init -input=false
# Phase 1: ensure artifacts bucket exists before upload
terraform apply -auto-approve -input=false \
  -target=aws_s3_bucket.artifacts \
  -target=aws_s3_bucket_versioning.artifacts

ARTIFACTS_BUCKET="$(terraform output -raw artifacts_bucket 2>/dev/null || true)"
if [ -z "$ARTIFACTS_BUCKET" ]; then
  ARTIFACTS_BUCKET="$DEFAULT_BUCKET"
  echo "artifacts_bucket output empty; using default ${ARTIFACTS_BUCKET}"
fi

aws s3 cp "$API_ZIP" "s3://${ARTIFACTS_BUCKET}/lambda/api.zip"
aws s3 cp "$WORKER_ZIP" "s3://${ARTIFACTS_BUCKET}/lambda/worker.zip"
terraform apply -auto-approve -input=false

# Terraform often no-ops code when s3_key is unchanged; publish + move live alias.
"$PUBLISH" "$API_FN" "$ARTIFACTS_BUCKET" "lambda/api.zip"
"$PUBLISH" "$WORKER_FN" "$ARTIFACTS_BUCKET" "lambda/worker.zip"

echo "Deploy ${ENV} complete (terraform apply + live aliases updated)"
