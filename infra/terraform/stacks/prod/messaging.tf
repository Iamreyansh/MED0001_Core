resource "aws_sqs_queue" "dlq" {
  name                      = "${local.name}-domain-events-dlq"
  message_retention_seconds = 1209600
}

resource "aws_sqs_queue" "domain_events" {
  name                       = "${local.name}-domain-events"
  visibility_timeout_seconds = 120
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq.arn
    maxReceiveCount     = 5
  })
}

# Dose reminders / weekly settlements — worker consumes via same domain-events queue.
resource "aws_scheduler_schedule_group" "this" {
  name = "${local.name}-schedules"
}

resource "aws_cloudwatch_metric_alarm" "domain_events_oldest" {
  alarm_name          = "${local.name}-domain-events-age"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "ApproximateAgeOfOldestMessage"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Maximum"
  threshold           = 300
  alarm_description   = "Domain events queue older than 5m (RPO/lag)"
  dimensions = {
    QueueName = aws_sqs_queue.domain_events.name
  }
}

resource "aws_cloudwatch_metric_alarm" "domain_events_dlq" {
  alarm_name          = "${local.name}-domain-events-dlq"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Maximum"
  threshold           = 0
  alarm_description   = "Domain events DLQ is not empty"
  dimensions = {
    QueueName = aws_sqs_queue.dlq.name
  }
}

# Published by API HealthController / ops as custom metric when outbox lag exceeds RPO.
resource "aws_cloudwatch_metric_alarm" "outbox_oldest_age" {
  alarm_name          = "${local.name}-outbox-age"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "OutboxOldestPendingAgeSeconds"
  namespace           = "MedMate/${local.environment}"
  period              = 60
  statistic           = "Maximum"
  threshold           = 900
  treat_missing_data  = "notBreaching"
  alarm_description   = "Transactional outbox oldest pending age > 15m (RPO)"
}
