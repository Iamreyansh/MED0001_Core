resource "tls_private_key" "jwt" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "aws_secretsmanager_secret" "jwt" {
  name       = "${local.name}/jwt-rs256"
  kms_key_id = aws_kms_key.this.arn
}

resource "aws_secretsmanager_secret_version" "jwt" {
  secret_id = aws_secretsmanager_secret.jwt.id
  secret_string = jsonencode({
    private_key_pem = tls_private_key.jwt.private_key_pem_pkcs8
    public_key_pem  = tls_private_key.jwt.public_key_pem
  })
}

resource "random_bytes" "mfa_key" {
  length = 32
}

resource "random_bytes" "payment_key" {
  length = 32
}

resource "aws_secretsmanager_secret" "mfa" {
  name       = "${local.name}/mfa-aes"
  kms_key_id = aws_kms_key.this.arn
}

resource "aws_secretsmanager_secret_version" "mfa" {
  secret_id = aws_secretsmanager_secret.mfa.id
  secret_string = jsonencode({
    encryption_key_base64         = random_bytes.mfa_key.base64
    payment_encryption_key_base64 = random_bytes.payment_key.base64
  })
}

data "aws_secretsmanager_secret" "maps_geocode" {
  name = "${local.name}/maps-geocode"
}

# Staging placeholders so API boot guards pass. Replace Cashfree app/secret keys
# out-of-band; TF will not overwrite secret_string after create.
resource "random_password" "cashfree_webhook" {
  length  = 48
  special = false
}

resource "random_password" "cashfree_payouts_webhook" {
  length  = 48
  special = false
}

resource "random_password" "kyc_webhook" {
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "cashfree" {
  name       = "${local.name}/cashfree"
  kms_key_id = aws_kms_key.this.arn
}

resource "aws_secretsmanager_secret_version" "cashfree" {
  secret_id = aws_secretsmanager_secret.cashfree.id
  secret_string = jsonencode({
    app_id                 = "placeholder_cashfree_app_id"
    secret_key             = "placeholder_cashfree_secret_key"
    webhook_secret         = random_password.cashfree_webhook.result
    payouts_client_id      = "placeholder_cashfree_payouts_client_id"
    payouts_client_secret  = "placeholder_cashfree_payouts_client_secret"
    payouts_webhook_secret = random_password.cashfree_payouts_webhook.result
    mode                   = "TEST"
  })

  lifecycle {
    ignore_changes = [secret_string]
  }
}

resource "aws_secretsmanager_secret" "kyc" {
  name       = "${local.name}/kyc"
  kms_key_id = aws_kms_key.this.arn
}

resource "aws_secretsmanager_secret_version" "kyc" {
  secret_id = aws_secretsmanager_secret.kyc.id
  secret_string = jsonencode({
    webhook_secret = random_password.kyc_webhook.result
  })

  lifecycle {
    ignore_changes = [secret_string]
  }
}

resource "random_password" "sms_webhook" {
  length  = 48
  special = false
}

resource "random_password" "internal_token" {
  length  = 64
  special = false
}

resource "aws_secretsmanager_secret" "internal" {
  name       = "${local.name}/internal"
  kms_key_id = aws_kms_key.this.arn
}

resource "aws_secretsmanager_secret_version" "internal" {
  secret_id = aws_secretsmanager_secret.internal.id
  secret_string = jsonencode({
    service_token = random_password.internal_token.result
  })

  lifecycle {
    ignore_changes = [secret_string]
  }
}

resource "aws_secretsmanager_secret" "comms" {
  name       = "${local.name}/comms"
  kms_key_id = aws_kms_key.this.arn
}

resource "aws_secretsmanager_secret_version" "comms" {
  secret_id = aws_secretsmanager_secret.comms.id
  secret_string = jsonencode({
    twilio_account_sid       = "replace_me"
    twilio_auth_token        = "replace_me"
    twilio_api_key           = "replace_me"
    twilio_from_number       = "replace_me"
    fcm_project_id           = "replace_me"
    fcm_service_account_json = "replace_me"
    sms_webhook_secret       = random_password.sms_webhook.result
  })

  lifecycle {
    ignore_changes = [secret_string]
  }
}
