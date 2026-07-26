# Public media CDN → private uploads bucket (OAC). Private prefixes are blocked at the edge.

resource "aws_acm_certificate" "cdn" {
  provider          = aws.us_east_1
  domain_name       = local.cdn_domain_name
  validation_method = "DNS"
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "cdn_cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.cdn.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  }
  zone_id = local.hosted_zone_id
  name    = each.value.name
  type    = each.value.type
  records = [each.value.record]
  ttl     = 60
}

resource "aws_acm_certificate_validation" "cdn" {
  provider                = aws.us_east_1
  certificate_arn         = aws_acm_certificate.cdn.arn
  validation_record_fqdns = [for r in aws_route53_record.cdn_cert_validation : r.fqdn]
}

resource "aws_cloudfront_origin_access_control" "uploads" {
  name                              = "${local.name}-uploads-oac"
  description                       = "OAC for ${local.name} uploads bucket"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_function" "public_prefixes_only" {
  name    = "${local.name}-cdn-public-prefixes"
  runtime = "cloudfront-js-2.0"
  comment = "Allow only public StorageObjectKeys prefixes"
  publish = true
  code    = <<-EOF
    function handler(event) {
      var uri = event.request.uri;
      var allowed = ${jsonencode([for p in local.cdn_public_prefixes : "/${p}"])};
      for (var i = 0; i < allowed.length; i++) {
        if (uri.indexOf(allowed[i]) === 0) {
          return event.request;
        }
      }
      return {
        statusCode: 403,
        statusDescription: 'Forbidden',
        headers: {
          'content-type': { value: 'text/plain' }
        },
        body: 'Forbidden'
      };
    }
  EOF
}

resource "aws_cloudfront_distribution" "cdn" {
  enabled             = true
  is_ipv6_enabled     = true
  comment             = "${local.name} public media CDN"
  aliases             = [local.cdn_domain_name]
  price_class         = "PriceClass_200"
  default_root_object = ""
  wait_for_deployment = true

  origin {
    domain_name              = aws_s3_bucket.uploads.bucket_regional_domain_name
    origin_id                = "uploads"
    origin_access_control_id = aws_cloudfront_origin_access_control.uploads.id
  }

  default_cache_behavior {
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    target_origin_id       = "uploads"
    viewer_protocol_policy = "redirect-to-https"
    compress               = true

    # AWS managed CachingOptimized
    cache_policy_id = "658327ea-f89d-4fab-a63d-7e88639e58f6"

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.public_prefixes_only.arn
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.cdn.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}

resource "aws_route53_record" "cdn" {
  zone_id = local.hosted_zone_id
  name    = local.cdn_domain_name
  type    = "A"
  alias {
    name                   = aws_cloudfront_distribution.cdn.domain_name
    zone_id                = aws_cloudfront_distribution.cdn.hosted_zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "cdn_aaaa" {
  zone_id = local.hosted_zone_id
  name    = local.cdn_domain_name
  type    = "AAAA"
  alias {
    name                   = aws_cloudfront_distribution.cdn.domain_name
    zone_id                = aws_cloudfront_distribution.cdn.hosted_zone_id
    evaluate_target_health = false
  }
}

resource "aws_s3_bucket_policy" "uploads_cdn" {
  bucket = aws_s3_bucket.uploads.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "AllowCloudFrontServicePrincipalRead"
      Effect = "Allow"
      Principal = {
        Service = "cloudfront.amazonaws.com"
      }
      Action   = ["s3:GetObject"]
      Resource = ["${aws_s3_bucket.uploads.arn}/*"]
      Condition = {
        StringEquals = {
          "AWS:SourceArn" = aws_cloudfront_distribution.cdn.arn
        }
      }
    }]
  })
}

# Replace default key policy so CloudFront OAC can decrypt SSE-KMS objects.
resource "aws_kms_key_policy" "this" {
  key_id = aws_kms_key.this.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "EnableRootAccountAdmin"
        Effect = "Allow"
        Principal = {
          AWS = "arn:aws:iam::${local.account_id}:root"
        }
        Action   = "kms:*"
        Resource = "*"
      },
      {
        Sid    = "AllowCloudFrontServicePrincipalSSEKMS"
        Effect = "Allow"
        Principal = {
          Service = "cloudfront.amazonaws.com"
        }
        Action = [
          "kms:Decrypt",
          "kms:Encrypt",
          "kms:GenerateDataKey*"
        ]
        Resource = "*"
        Condition = {
          StringEquals = {
            "AWS:SourceArn" = aws_cloudfront_distribution.cdn.arn
          }
        }
      }
    ]
  })
}
