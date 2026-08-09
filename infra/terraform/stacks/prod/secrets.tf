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

# Placeholders so API boot guards pass. Replace vendor keys/webhook secrets
# out-of-band; TF will not overwrite secret_string after create.
resource "random_password" "razorpay_webhook" {
  length  = 48
  special = false
}

resource "random_password" "razorpayx_webhook" {
  length  = 48
  special = false
}

resource "random_password" "kyc_webhook" {
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "razorpay" {
  name       = "${local.name}/razorpay"
  kms_key_id = aws_kms_key.this.arn
}

resource "aws_secretsmanager_secret_version" "razorpay" {
  secret_id = aws_secretsmanager_secret.razorpay.id
  secret_string = jsonencode({
    key_id         = "rzp_live_replace_me"
    key_secret     = "replace_me"
    webhook_secret = random_password.razorpay_webhook.result
  })

  lifecycle {
    ignore_changes = [secret_string]
  }
}

resource "aws_secretsmanager_secret" "razorpayx" {
  name       = "${local.name}/razorpayx"
  kms_key_id = aws_kms_key.this.arn
}

resource "aws_secretsmanager_secret_version" "razorpayx" {
  secret_id = aws_secretsmanager_secret.razorpayx.id
  secret_string = jsonencode({
    key_id         = "rzp_live_replace_me"
    key_secret     = "replace_me"
    webhook_secret = random_password.razorpayx_webhook.result
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
