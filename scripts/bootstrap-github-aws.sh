#!/usr/bin/env bash
# Bootstrap GitHub OIDC provider + med0001-gha-deploy role + repo variable.
# Requires: aws CLI (account 105927215604), gh CLI with repo admin.
set -euo pipefail

ACCOUNT_ID="${AWS_ACCOUNT_ID:-105927215604}"
REGION="${AWS_REGION:-ap-south-1}"
ROLE_NAME="${DEPLOY_ROLE_NAME:-med0001-gha-deploy}"
STATE_BUCKET="${STATE_BUCKET:-terraform-locks-105927215604}"
REPO_SLUG="${REPO_SLUG:-Iamreyansh/MED0001_Core}"
OIDC_URL="token.actions.githubusercontent.com"
OIDC_ARN="arn:aws:iam::${ACCOUNT_ID}:oidc-provider/${OIDC_URL}"
ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/${ROLE_NAME}"

THUMBPRINTS=(
  6938fd4d98bab03faadb97b34396831e3780aea1
  1c58a3a8518e8759bf075b76b750d4f2df264fcd
  2d74d6dfd96eea55ad7baafa0d3c6552b2dadc37
  227203b5317f3818cab5b5ce596132bf36748c0e
)

need() { command -v "$1" >/dev/null || { echo "missing: $1" >&2; exit 1; }; }
need aws
need gh
need python3
need jq

echo "==> AWS identity"
CALLER="$(aws sts get-caller-identity --output json)"
echo "$CALLER" | jq -c '{Account,Arn}'
ACTUAL_ACCOUNT="$(echo "$CALLER" | jq -r .Account)"
if [ "$ACTUAL_ACCOUNT" != "$ACCOUNT_ID" ]; then
  echo "Expected account ${ACCOUNT_ID}, got ${ACTUAL_ACCOUNT}" >&2
  exit 1
fi

echo "==> Resolve GitHub repo (${REPO_SLUG})"
REPO_JSON="$(gh api "repos/${REPO_SLUG}")"
OWNER_LOGIN="$(echo "$REPO_JSON" | jq -r .owner.login)"
OWNER_ID="$(echo "$REPO_JSON" | jq -r .owner.id)"
REPO_NAME="$(echo "$REPO_JSON" | jq -r .name)"
REPO_ID="$(echo "$REPO_JSON" | jq -r .id)"
SUB_CLASSIC="repo:${OWNER_LOGIN}/${REPO_NAME}:*"
SUB_WITH_IDS="repo:${OWNER_LOGIN}@${OWNER_ID}/${REPO_NAME}@${REPO_ID}:*"

echo "==> Ensure GitHub OIDC provider"
if aws iam get-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN" >/dev/null 2>&1; then
  aws iam update-open-id-connect-provider-thumbprint \
    --open-id-connect-provider-arn "$OIDC_ARN" \
    --thumbprint-list "${THUMBPRINTS[@]}"
else
  aws iam create-open-id-connect-provider \
    --url "https://${OIDC_URL}" \
    --client-id-list sts.amazonaws.com \
    --thumbprint-list "${THUMBPRINTS[@]}" \
    --tags Key=Project,Value=MED0001
fi

TRUST="$(python3 - <<PY
import json
print(json.dumps({
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Federated": "${OIDC_ARN}"},
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {"${OIDC_URL}:aud": "sts.amazonaws.com"},
      "StringLike": {"${OIDC_URL}:sub": ["${SUB_CLASSIC}", "${SUB_WITH_IDS}"]}
    }
  }]
}, indent=2))
PY
)"

echo "==> Ensure deploy role ${ROLE_NAME}"
if aws iam get-role --role-name "$ROLE_NAME" >/dev/null 2>&1; then
  aws iam update-assume-role-policy --role-name "$ROLE_NAME" --policy-document "$TRUST"
else
  aws iam create-role \
    --role-name "$ROLE_NAME" \
    --assume-role-policy-document "$TRUST" \
    --description "GitHub Actions OIDC deploy for ${REPO_SLUG}" \
    --tags Key=Project,Value=MED0001
fi

# Broad deploy policy — Terraform apply needs wide IAM; tighten later via IAM Access Analyzer.
POLICY="$(python3 - <<PY
import json
print(json.dumps({
  "Version": "2012-10-17",
  "Statement": [
    {"Effect": "Allow", "Action": [
      "ec2:*","ecs:*","ecr:*","elasticloadbalancing:*","rds:*","elasticache:*",
      "sqs:*","s3:*","kms:*","secretsmanager:*","logs:*","cloudwatch:*",
      "sns:*","route53:*","acm:*","iam:*","scheduler:*","budgets:*","tag:*",
      "application-autoscaling:*"
    ], "Resource": "*"},
    {"Effect": "Allow", "Action": ["sts:GetCallerIdentity"], "Resource": "*"}
  ]
}, indent=2))
PY
)"
aws iam put-role-policy --role-name "$ROLE_NAME" --policy-name med0001-gha-terraform --policy-document "$POLICY"

echo "==> Set GitHub repo variable AWS_DEPLOY_ROLE_ARN"
gh variable set AWS_DEPLOY_ROLE_ARN --repo "$REPO_SLUG" --body "$ROLE_ARN"

for ENV in staging production; do
  gh api --method PUT "repos/${REPO_SLUG}/environments/${ENV}" >/dev/null || true
done

echo "OK: role=${ROLE_ARN} state_bucket=${STATE_BUCKET} region=${REGION}"
