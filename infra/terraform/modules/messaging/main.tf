terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.80"
    }
  }
}

variable "name" { type = string }

resource "aws_sqs_queue" "dlq" {
  name                      = "${var.name}-domain-events-dlq"
  message_retention_seconds = 1209600
}

resource "aws_sqs_queue" "domain_events" {
  name                       = "${var.name}-domain-events"
  visibility_timeout_seconds = 60
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq.arn
    maxReceiveCount     = 5
  })
}

output "domain_events_queue_url" { value = aws_sqs_queue.domain_events.url }
output "domain_events_queue_arn" { value = aws_sqs_queue.domain_events.arn }
output "dlq_arn" { value = aws_sqs_queue.dlq.arn }
