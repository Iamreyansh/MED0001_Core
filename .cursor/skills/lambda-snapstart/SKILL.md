---
name: lambda-snapstart
description: Package and publish Spring Boot Lambda with SnapStart priming and alias live. Use when packaging or deploying api/worker Lambdas.
---

# Lambda SnapStart

## Steps

1. Build/package:

```bash
make package
# or: make package-api / make package-worker
# underlying: infra/lambda/package.sh {api|worker}
```

2. Deploy a **published version** (never `$LATEST` in prod) via Terraform / `deploy-main`.
3. Ensure SnapStart is enabled on published versions; CRaC priming resource registered; `afterRestore` reconnect guidance followed in app code (`SnapStartPriming` etc.).
4. Point alias `live` at the new version; set provisioned concurrency via Terraform var when needed.
5. Smoke:

```bash
curl -sS https://core.api.nammamedmate.com/api/v1/health   # prod
# staging host per stack outputs / README
```

## Constraints

- Runtime: Java 21, arm64
- Fat jar packaged by `package.sh` into `infra/lambda/{api,worker}.zip`
- Alias `live` is the traffic pointer for APIGW/integrations

## Done when

New version published, `live` updated, health UP on the target env.
