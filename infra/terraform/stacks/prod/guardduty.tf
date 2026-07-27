# GuardDuty Malware Protection for S3 — KYC prefix only.
# Flow: PutObject → GuardDuty scan → EventBridge → SQS → worker soft-deletes infected keys.

resource "aws_iam_role" "guardduty_malware_s3" {
  name = "${local.name}-guardduty-malware-s3"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "malware-protection-plan.guardduty.amazonaws.com"
      }
      Action = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "guardduty_malware_s3" {
  name = "${local.name}-guardduty-malware-s3"
  role = aws_iam_role.guardduty_malware_s3.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowManagedRuleToSendS3EventsToGuardDuty"
        Effect = "Allow"
        Action = [
          "events:PutRule",
          "events:DeleteRule",
          "events:PutTargets",
          "events:RemoveTargets"
        ]
        Resource = [
          "arn:aws:events:${data.aws_region.current.region}:${local.account_id}:rule/DO-NOT-DELETE-AmazonGuardDutyMalwareProtectionS3*"
        ]
        Condition = {
          StringLike = {
            "events:ManagedBy" = "malware-protection-plan.guardduty.amazonaws.com"
          }
        }
      },
      {
        Sid    = "AllowGuardDutyToMonitorEventBridgeManagedRule"
        Effect = "Allow"
        Action = [
          "events:DescribeRule",
          "events:ListTargetsByRule"
        ]
        Resource = [
          "arn:aws:events:${data.aws_region.current.region}:${local.account_id}:rule/DO-NOT-DELETE-AmazonGuardDutyMalwareProtectionS3*"
        ]
      },
      {
        Sid    = "AllowPostScanTag"
        Effect = "Allow"
        Action = [
          "s3:PutObjectTagging",
          "s3:GetObjectTagging",
          "s3:PutObjectVersionTagging",
          "s3:GetObjectVersionTagging"
        ]
        Resource = ["${aws_s3_bucket.uploads.arn}/kyc/*"]
      },
      {
        Sid    = "AllowEnableS3EventBridgeEvents"
        Effect = "Allow"
        Action = [
          "s3:PutBucketNotification",
          "s3:GetBucketNotification"
        ]
        Resource = [aws_s3_bucket.uploads.arn]
      },
      {
        Sid    = "AllowPutValidationObject"
        Effect = "Allow"
        Action = ["s3:PutObject"]
        Resource = [
          "${aws_s3_bucket.uploads.arn}/malware-protection-resource-validation-object"
        ]
      },
      {
        Sid      = "AllowCheckBucketOwnership"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = [aws_s3_bucket.uploads.arn]
      },
      {
        Sid    = "AllowMalwareScan"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:GetObjectVersion"
        ]
        Resource = ["${aws_s3_bucket.uploads.arn}/kyc/*"]
      },
      {
        Sid    = "AllowDecryptForMalwareScan"
        Effect = "Allow"
        Action = [
          "kms:GenerateDataKey",
          "kms:Decrypt"
        ]
        Resource = [aws_kms_key.this.arn]
        Condition = {
          StringLike = {
            "kms:ViaService" = "s3.${data.aws_region.current.region}.amazonaws.com"
          }
        }
      }
    ]
  })
}

resource "aws_guardduty_malware_protection_plan" "uploads_kyc" {
  role = aws_iam_role.guardduty_malware_s3.arn

  protected_resource {
    s3_bucket {
      bucket_name     = aws_s3_bucket.uploads.id
      object_prefixes = ["kyc/"]
    }
  }

  actions {
    tagging {
      status = "ENABLED"
    }
  }

  tags = {
    Name = "${local.name}-kyc-malware"
  }
}

resource "aws_sqs_queue" "kyc_malware_dlq" {
  name                      = "${local.name}-kyc-malware-dlq"
  message_retention_seconds = 1209600
}

resource "aws_sqs_queue" "kyc_malware_scans" {
  name                       = "${local.name}-kyc-malware-scans"
  visibility_timeout_seconds = 120
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.kyc_malware_dlq.arn
    maxReceiveCount     = 5
  })
}

resource "aws_cloudwatch_event_rule" "kyc_malware_scan_result" {
  name = "${local.name}-kyc-malware-scan"
  event_pattern = jsonencode({
    source      = ["aws.guardduty"]
    detail-type = ["GuardDuty Malware Protection Object Scan Result"]
    detail = {
      s3ObjectDetails = {
        bucketName = [aws_s3_bucket.uploads.bucket]
      }
    }
  })
}

resource "aws_cloudwatch_event_target" "kyc_malware_scan_sqs" {
  rule      = aws_cloudwatch_event_rule.kyc_malware_scan_result.name
  target_id = "kyc-malware-sqs"
  arn       = aws_sqs_queue.kyc_malware_scans.arn
}

resource "aws_sqs_queue_policy" "kyc_malware_scans" {
  queue_url = aws_sqs_queue.kyc_malware_scans.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "AllowEventBridgeSend"
      Effect = "Allow"
      Principal = {
        Service = "events.amazonaws.com"
      }
      Action   = ["sqs:SendMessage"]
      Resource = [aws_sqs_queue.kyc_malware_scans.arn]
      Condition = {
        ArnEquals = {
          "aws:SourceArn" = aws_cloudwatch_event_rule.kyc_malware_scan_result.arn
        }
      }
    }]
  })
}

resource "aws_cloudwatch_metric_alarm" "kyc_malware_dlq_depth" {
  alarm_name          = "${local.name}-kyc-malware-dlq-depth"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Maximum"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  dimensions = {
    QueueName = aws_sqs_queue.kyc_malware_dlq.name
  }
}
