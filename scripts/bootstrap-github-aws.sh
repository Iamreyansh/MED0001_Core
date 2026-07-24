#!/usr/bin/env bash
# Idempotent bootstrap: GitHub OIDC provider + deploy role + repo variable + environments.
# Requires: aws CLI (account 105927215604), gh CLI with repo admin for environments.
set -euo pipefail

ACCOUNT_ID="${AWS_ACCOUNT_ID:-105927215604}"
REGION="${AWS_REGION:-ap-south-1}"
ROLE_NAME="${DEPLOY_ROLE_NAME:-med0001-gha-deploy}"
STATE_BUCKET="${STATE_BUCKET:-terraform-locks-105927215604}"
REPO_SLUG="${REPO_SLUG:-Iamreyansh/MED0001_Core}"
OIDC_URL="token.actions.githubusercontent.com"
OIDC_ARN="arn:aws:iam::${ACCOUNT_ID}:oidc-provider/${OIDC_URL}"
ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/${ROLE_NAME}"

# GitHub Actions OIDC intermediate CA thumbprints (keep several for rotation).
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

echo "==> Resolve GitHub repo ids (${REPO_SLUG})"
REPO_JSON="$(gh api "repos/${REPO_SLUG}")"
OWNER_LOGIN="$(echo "$REPO_JSON" | jq -r .owner.login)"
OWNER_ID="$(echo "$REPO_JSON" | jq -r .owner.id)"
REPO_NAME="$(echo "$REPO_JSON" | jq -r .name)"
REPO_ID="$(echo "$REPO_JSON" | jq -r .id)"
# Classic sub: repo:ORG/REPO:…
SUB_CLASSIC="repo:${OWNER_LOGIN}/${REPO_NAME}:*"
# GitHub may emit numeric ids: repo:ORG@ownerId/REPO@repoId:…
SUB_WITH_IDS="repo:${OWNER_LOGIN}@${OWNER_ID}/${REPO_NAME}@${REPO_ID}:*"
echo "sub patterns: ${SUB_CLASSIC} | ${SUB_WITH_IDS}"

echo "==> Ensure GitHub OIDC provider"
if aws iam get-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN" >/dev/null 2>&1; then
  aws iam update-open-id-connect-provider-thumbprint \
    --open-id-connect-provider-arn "$OIDC_ARN" \
    --thumbprint-list "${THUMBPRINTS[@]}"
  # Client IDs cannot be updated in-place easily; verify audience exists.
  CLIENTS="$(aws iam get-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN" --query ClientIDList --output text)"
  if ! grep -qw 'sts.amazonaws.com' <<<"$CLIENTS"; then
    aws iam add-client-id-to-open-id-connect-provider \
      --open-id-connect-provider-arn "$OIDC_ARN" \
      --client-id sts.amazonaws.com
  fi
  echo "OIDC provider updated: $OIDC_ARN"
else
  aws iam create-open-id-connect-provider \
    --url "https://${OIDC_URL}" \
    --client-id-list sts.amazonaws.com \
    --thumbprint-list "${THUMBPRINTS[@]}" \
    --tags Key=Project,Value=MED0001
  echo "OIDC provider created: $OIDC_ARN"
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
      "StringLike": {
        "${OIDC_URL}:sub": ["${SUB_CLASSIC}", "${SUB_WITH_IDS}"]
      }
    }
  }]
}, indent=2))
PY
)"

echo "==> Ensure deploy role ${ROLE_NAME}"
if aws iam get-role --role-name "$ROLE_NAME" >/dev/null 2>&1; then
  aws iam update-assume-role-policy --role-name "$ROLE_NAME" --policy-document "$TRUST"
  echo "Updated trust policy on ${ROLE_ARN}"
else
  aws iam create-role \
    --role-name "$ROLE_NAME" \
    --assume-role-policy-document "$TRUST" \
    --description "GitHub Actions OIDC deploy role for ${REPO_SLUG}" \
    --tags Key=Project,Value=MED0001
  echo "Created ${ROLE_ARN}"
fi

ADMIN_POLICY="$(python3 - <<'PY'
import json
print(json.dumps({
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "DeployBootstrap",
    "Effect": "Allow",
    "Action": "*",
    "Resource": "*"
  }]
}))
PY
)"
aws iam put-role-policy \
  --role-name "$ROLE_NAME" \
  --policy-name med0001-gha-adminish \
  --policy-document "$ADMIN_POLICY"
echo "Inline deploy policy applied (adminish — tighten later if desired)"

echo "==> Ensure state bucket prefix exists"
aws s3api head-bucket --bucket "$STATE_BUCKET" >/dev/null
# placeholder object so prefix is visible
aws s3api put-object \
  --bucket "$STATE_BUCKET" \
  --key "MED0001/.keep" \
  --body /dev/null >/dev/null 2>&1 || true
echo "State: s3://${STATE_BUCKET}/MED0001/"

echo "==> Ensure AWS service-linked roles (RDS / ElastiCache)"
ensure_slr() {
  local service="$1"
  if aws iam create-service-linked-role --aws-service-name "$service" >/dev/null 2>&1; then
    echo "Created service-linked role for ${service}"
  else
    echo "Service-linked role OK for ${service}"
  fi
}
ensure_slr rds.amazonaws.com
ensure_slr elasticache.amazonaws.com

echo "==> GitHub repository variable AWS_DEPLOY_ROLE_ARN"
gh variable set AWS_DEPLOY_ROLE_ARN --repo "$REPO_SLUG" --body "$ROLE_ARN"
gh variable list --repo "$REPO_SLUG"

echo "==> GitHub environments (staging, prod)"
for ENV_NAME in staging prod; do
  if gh api -X PUT "repos/${REPO_SLUG}/environments/${ENV_NAME}" \
    -f wait_timer=0 \
    -F reviewers='[]' \
    -F deployment_branch_policy='null' >/dev/null 2>&1; then
    echo "Environment OK: ${ENV_NAME}"
  else
    echo "WARN: could not create/update environment '${ENV_NAME}' (need repo admin)." >&2
    echo "      Ask ${OWNER_LOGIN} to create it, or re-run with an admin gh token." >&2
  fi
done

echo
echo "Done."
echo "  Role:   ${ROLE_ARN}"
echo "  Trust:  ${SUB_CLASSIC}"
echo "          ${SUB_WITH_IDS}"
echo "  Next:   gh workflow run deploy-main.yml --repo ${REPO_SLUG}"
echo "          (or re-run the failed deploy-staging job)"
