#!/usr/bin/env bash
set -euo pipefail
# One-time helpers: ensure state bucket prefix, print OIDC role setup hints.
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
echo "Account=$ACCOUNT"
echo "State bucket=s3://terraform-locks-105927215604/MED0001/"
echo "After staging terraform apply, set repo variable AWS_DEPLOY_ROLE_ARN to module.ci output."
