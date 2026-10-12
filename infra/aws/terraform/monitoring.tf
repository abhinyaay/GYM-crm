resource "aws_sns_topic" "alarms" {
  name = "${local.name}-alarms"
}

resource "aws_sns_topic_subscription" "email" {
  count     = var.alarm_email == "" ? 0 : 1
  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

locals {
  alarms = {
    alb_5xx = {
      namespace  = "AWS/ApplicationELB", metric = "HTTPCode_Target_5XX_Count", stat = "Sum", threshold = 20,
      period     = 300, comparison = "GreaterThanThreshold",
      dimensions = { LoadBalancer = aws_lb.main.arn_suffix }
    }
    core_unhealthy = {
      namespace  = "AWS/ApplicationELB", metric = "UnHealthyHostCount", stat = "Maximum", threshold = 0,
      period     = 60, comparison = "GreaterThanThreshold",
      dimensions = { LoadBalancer = aws_lb.main.arn_suffix, TargetGroup = aws_lb_target_group.core.arn_suffix }
    }
    core_p95_latency = {
      namespace  = "AWS/ApplicationELB", metric = "TargetResponseTime", stat = "p95", threshold = 1.5,
      period     = 300, comparison = "GreaterThanThreshold",
      dimensions = { LoadBalancer = aws_lb.main.arn_suffix, TargetGroup = aws_lb_target_group.core.arn_suffix }
    }
    rds_cpu = {
      namespace  = "AWS/RDS", metric = "CPUUtilization", stat = "Average", threshold = 80,
      period     = 300, comparison = "GreaterThanThreshold",
      dimensions = { DBInstanceIdentifier = aws_db_instance.postgres.identifier }
    }
    rds_storage = {
      namespace  = "AWS/RDS", metric = "FreeStorageSpace", stat = "Minimum", threshold = 5368709120,
      period     = 300, comparison = "LessThanThreshold",
      dimensions = { DBInstanceIdentifier = aws_db_instance.postgres.identifier }
    }
    redis_memory = {
      namespace  = "AWS/ElastiCache", metric = "DatabaseMemoryUsagePercentage", stat = "Average", threshold = 80,
      period     = 300, comparison = "GreaterThanThreshold",
      dimensions = { ReplicationGroupId = aws_elasticache_replication_group.redis.id }
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "this" {
  for_each            = local.alarms
  alarm_name          = "${local.name}-${each.key}"
  namespace           = each.value.namespace
  metric_name         = each.value.metric
  statistic           = each.value.stat == "p95" ? null : each.value.stat
  extended_statistic  = each.value.stat == "p95" ? "p95" : null
  period              = each.value.period
  evaluation_periods  = 2
  threshold           = each.value.threshold
  comparison_operator = each.value.comparison
  dimensions          = each.value.dimensions
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
}

# Application error log metric: count ERROR lines in the structured JSON logs of the core service.
resource "aws_cloudwatch_log_metric_filter" "core_errors" {
  name           = "${local.name}-core-errors"
  log_group_name = aws_cloudwatch_log_group.service["core-service"].name
  pattern        = "{ $.level = \"ERROR\" }"
  metric_transformation {
    name      = "CoreErrorLogCount"
    namespace = "GymOps"
    value     = "1"
  }
}

resource "aws_cloudwatch_metric_alarm" "core_errors" {
  alarm_name          = "${local.name}-core-error-logs"
  namespace           = "GymOps"
  metric_name         = "CoreErrorLogCount"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 10
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
}
