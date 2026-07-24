terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.80"
    }
    tls = {
      source  = "hashicorp/tls"
      version = ">= 4.0"
    }
  }
}

variable "name" { type = string }
variable "kms_key_arn" {
  type    = string
  default = ""
}

resource "tls_private_key" "jwt" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "aws_secretsmanager_secret" "jwt" {
  name       = "${var.name}/jwt-rs256"
  kms_key_id = var.kms_key_arn
}

resource "aws_secretsmanager_secret_version" "jwt" {
  secret_id = aws_secretsmanager_secret.jwt.id
  secret_string = jsonencode({
    private_key_pem = tls_private_key.jwt.private_key_pem_pkcs8
    public_key_pem  = tls_private_key.jwt.public_key_pem
  })
}

output "jwt_secret_arn" { value = aws_secretsmanager_secret.jwt.arn }
output "jwt_public_key_pem" {
  value     = tls_private_key.jwt.public_key_pem
  sensitive = true
}
output "jwt_private_key_pem" {
  value     = tls_private_key.jwt.private_key_pem_pkcs8
  sensitive = true
}
