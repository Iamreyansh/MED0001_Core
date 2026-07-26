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

resource "aws_secretsmanager_secret" "mfa" {
  name       = "${local.name}/mfa-aes"
  kms_key_id = aws_kms_key.this.arn
}

resource "aws_secretsmanager_secret_version" "mfa" {
  secret_id = aws_secretsmanager_secret.mfa.id
  secret_string = jsonencode({
    encryption_key_base64 = random_bytes.mfa_key.base64
  })
}

data "aws_secretsmanager_secret" "maps_geocode" {
  name = "${local.name}/maps-geocode"
}
