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
