#!/usr/bin/env bash
# Publish Lambda code from S3 and point alias "live" at the new version.
# Usage: publish-lambda.sh <function-name> <s3-bucket> <s3-key>
set -euo pipefail

FN="${1:?function-name required}"
BUCKET="${2:?s3-bucket required}"
KEY="${3:?s3-key required}"
ALIAS="${ALIAS:-live}"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-ap-south-1}}"

echo "Publishing ${FN} from s3://${BUCKET}/${KEY}"
VERSION="$(aws lambda update-function-code \
  --region "$REGION" \
  --function-name "$FN" \
  --s3-bucket "$BUCKET" \
  --s3-key "$KEY" \
  --publish \
  --query Version \
  --output text)"

echo "Waiting for ${FN} LastUpdateStatus=Successful"
aws lambda wait function-updated --region "$REGION" --function-name "$FN"

echo "Pointing ${FN}:${ALIAS} -> version ${VERSION}"
aws lambda update-alias \
  --region "$REGION" \
  --function-name "$FN" \
  --name "$ALIAS" \
  --function-version "$VERSION" \
  >/dev/null

echo "Published ${FN}:${ALIAS} -> ${VERSION}"
