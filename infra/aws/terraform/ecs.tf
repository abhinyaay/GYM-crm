resource "aws_ecs_cluster" "main" {
  name = local.name
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

# Private DNS for service-to-service calls (core -> analytics) that never leave the VPC.
resource "aws_service_discovery_private_dns_namespace" "internal" {
  name = "gymops.internal"
  vpc  = aws_vpc.main.id
}

resource "aws_service_discovery_service" "analytics" {
  name = "analytics"
  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.internal.id
    routing_policy = "MULTIVALUE"
    dns_records {
      ttl  = 10
      type = "A"
    }
  }
  health_check_custom_config {
    failure_threshold = 1
  }
}

resource "aws_cloudwatch_log_group" "service" {
  for_each          = toset(["core-service", "analytics-service", "web-service"])
  name              = "/gymops/${var.environment}/${each.key}"
  retention_in_days = 30
}

locals {
  app_secret = aws_secretsmanager_secret.app.arn
  db_secret  = aws_db_instance.postgres.master_user_secret[0].secret_arn
  core_secrets = concat([
    { name = "DB_PASSWORD", valueFrom = "${local.db_secret}:password::" },
    { name = "JWT_SECRET", valueFrom = "${local.app_secret}:JWT_SECRET::" },
    { name = "REDIS_PASSWORD", valueFrom = "${local.app_secret}:REDIS_PASSWORD::" },
    { name = "SIMULATED_GATEWAY_SECRET", valueFrom = "${local.app_secret}:SIMULATED_GATEWAY_SECRET::" },
    { name = "ACCESS_MASTER_KEY", valueFrom = "${local.app_secret}:ACCESS_MASTER_KEY::" },
    ], var.bootstrap_admin_email == "" ? [] : [
    { name = "BOOTSTRAP_ADMIN_PASSWORD", valueFrom = aws_secretsmanager_secret.bootstrap_admin.arn },
    ], var.payment_gateway != "razorpay" ? [] : [
    { name = "RAZORPAY_KEY_ID", valueFrom = "${aws_secretsmanager_secret.razorpay.arn}:key_id::" },
    { name = "RAZORPAY_KEY_SECRET", valueFrom = "${aws_secretsmanager_secret.razorpay.arn}:key_secret::" },
    { name = "RAZORPAY_WEBHOOK_SECRET", valueFrom = "${aws_secretsmanager_secret.razorpay.arn}:webhook_secret::" },
    ], var.email_provider != "smtp" ? [] : [
    { name = "SMTP_USERNAME", valueFrom = "${aws_secretsmanager_secret.smtp.arn}:username::" },
    { name = "SMTP_PASSWORD", valueFrom = "${aws_secretsmanager_secret.smtp.arn}:password::" },
    ], var.sms_provider != "twilio" ? [] : [
    { name = "TWILIO_ACCOUNT_SID", valueFrom = "${aws_secretsmanager_secret.sms.arn}:account_sid::" },
    { name = "TWILIO_AUTH_TOKEN", valueFrom = "${aws_secretsmanager_secret.sms.arn}:auth_token::" },
    ], var.sms_provider != "msg91" ? [] : [
    { name = "MSG91_AUTH_KEY", valueFrom = "${aws_secretsmanager_secret.sms.arn}:auth_key::" },
  ])
}

resource "aws_ecs_task_definition" "core" {
  family                   = "${local.name}-core"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 1024
  memory                   = 2048
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name         = "core-service"
    image        = "${aws_ecr_repository.service["core-service"].repository_url}:${var.image_tag}"
    essential    = true
    portMappings = [{ containerPort = 8080, protocol = "tcp" }]
    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
      { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.postgres.address}:5432/gymops?sslmode=require" },
      { name = "DB_USERNAME", value = "gymops" },
      { name = "REDIS_HOST", value = aws_elasticache_replication_group.redis.primary_endpoint_address },
      { name = "REDIS_SSL", value = "true" },
      { name = "ANALYTICS_BASE_URL", value = "http://analytics.gymops.internal:8000" },
      { name = "BOOTSTRAP_ADMIN_EMAIL", value = var.bootstrap_admin_email },
      { name = "DEMO_DATA_ENABLED", value = "false" },
      { name = "PAYMENT_GATEWAY", value = var.payment_gateway },
      { name = "PUBLIC_BASE_URL", value = var.public_base_url },
      { name = "EMAIL_PROVIDER", value = var.email_provider },
      { name = "SMTP_HOST", value = var.smtp_host },
      { name = "SMTP_PORT", value = tostring(var.smtp_port) },
      { name = "SMTP_STARTTLS_REQUIRED", value = "true" },
      { name = "MAIL_FROM_ADDRESS", value = var.mail_from_address },
      { name = "MAIL_FROM_NAME", value = var.mail_from_name },
      { name = "SMS_PROVIDER", value = var.sms_provider },
      { name = "TWILIO_FROM_NUMBER", value = lookup(var.sms_sender, "twilio_from_number", "") },
      { name = "TWILIO_MESSAGING_SERVICE_SID", value = lookup(var.sms_sender, "twilio_messaging_service_sid", "") },
      { name = "MSG91_SENDER_ID", value = lookup(var.sms_sender, "msg91_sender_id", "") },
      { name = "MSG91_TEMPLATE_ID", value = lookup(var.sms_sender, "msg91_template_id", "") },
      { name = "MSG91_DLT_TEMPLATE_ID", value = lookup(var.sms_sender, "msg91_dlt_template_id", "") },
      { name = "JAVA_TOOL_OPTIONS", value = "-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" },
    ]
    secrets = local.core_secrets
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.service["core-service"].name
        awslogs-region        = var.region
        awslogs-stream-prefix = "core"
      }
    }
    healthCheck = {
      command     = ["CMD-SHELL", "wget -qO- http://127.0.0.1:8080/actuator/health/liveness || exit 1"]
      interval    = 15
      timeout     = 5
      retries     = 3
      startPeriod = 90
    }
  }])
}

resource "aws_ecs_task_definition" "analytics" {
  family                   = "${local.name}-analytics"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.task.arn
  container_definitions = jsonencode([{
    name         = "analytics-service"
    image        = "${aws_ecr_repository.service["analytics-service"].repository_url}:${var.image_tag}"
    essential    = true
    portMappings = [{ containerPort = 8000, protocol = "tcp" }]
    environment = [
      { name = "BUSINESS_TIMEZONE", value = "Asia/Kolkata" },
      { name = "CACHE_TTL_SECONDS", value = "300" },
    ]
    secrets = [
      { name = "DATABASE_URL", valueFrom = "${local.app_secret}:ANALYTICS_DATABASE_URL::" },
      { name = "JWT_SECRET", valueFrom = "${local.app_secret}:JWT_SECRET::" },
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.service["analytics-service"].name
        awslogs-region        = var.region
        awslogs-stream-prefix = "analytics"
      }
    }
  }])
}

resource "aws_ecs_task_definition" "web" {
  family                   = "${local.name}-web"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.task.arn
  container_definitions = jsonencode([{
    name         = "web-service"
    image        = "${aws_ecr_repository.service["web-service"].repository_url}:${var.image_tag}"
    essential    = true
    portMappings = [{ containerPort = 8080, protocol = "tcp" }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.service["web-service"].name
        awslogs-region        = var.region
        awslogs-stream-prefix = "web"
      }
    }
  }])
}

resource "aws_ecs_service" "web" {
  name                              = "web-service"
  cluster                           = aws_ecs_cluster.main.id
  task_definition                   = aws_ecs_task_definition.web.arn
  desired_count                     = 2
  launch_type                       = "FARGATE"
  health_check_grace_period_seconds = 30
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = aws_subnet.app[*].id
    security_groups  = [aws_security_group.web.id]
    assign_public_ip = false
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.web.arn
    container_name   = "web-service"
    container_port   = 8080
  }
}

resource "aws_ecs_service" "core" {
  name                               = "core-service"
  cluster                            = aws_ecs_cluster.main.id
  task_definition                    = aws_ecs_task_definition.core.arn
  desired_count                      = var.core_desired_count
  launch_type                        = "FARGATE"
  health_check_grace_period_seconds  = 120
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  deployment_circuit_breaker {
    enable   = true
    rollback = true # failed rolling deployments roll back automatically
  }
  network_configuration {
    subnets          = aws_subnet.app[*].id
    security_groups  = [aws_security_group.core.id]
    assign_public_ip = false
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.core.arn
    container_name   = "core-service"
    container_port   = 8080
  }
  lifecycle {
    ignore_changes = [desired_count] # owned by auto scaling
  }
}

resource "aws_ecs_service" "analytics" {
  name                              = "analytics-service"
  cluster                           = aws_ecs_cluster.main.id
  task_definition                   = aws_ecs_task_definition.analytics.arn
  desired_count                     = var.analytics_desired_count
  launch_type                       = "FARGATE"
  health_check_grace_period_seconds = 60
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = aws_subnet.app[*].id
    security_groups  = [aws_security_group.analytics.id]
    assign_public_ip = false
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.analytics.arn
    container_name   = "analytics-service"
    container_port   = 8000
  }
  service_registries {
    registry_arn = aws_service_discovery_service.analytics.arn
  }
}

# ---------------------------------------------------------------- auto scaling (core)

resource "aws_appautoscaling_target" "core" {
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.core.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = var.core_desired_count
  max_capacity       = var.core_max_count
}

resource "aws_appautoscaling_policy" "core_cpu" {
  name               = "${local.name}-core-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.core.service_namespace
  resource_id        = aws_appautoscaling_target.core.resource_id
  scalable_dimension = aws_appautoscaling_target.core.scalable_dimension
  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

resource "aws_appautoscaling_policy" "core_requests" {
  name               = "${local.name}-core-requests"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.core.service_namespace
  resource_id        = aws_appautoscaling_target.core.resource_id
  scalable_dimension = aws_appautoscaling_target.core.scalable_dimension
  target_tracking_scaling_policy_configuration {
    target_value = 500 # requests per target per minute
    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = "${aws_lb.main.arn_suffix}/${aws_lb_target_group.core.arn_suffix}"
    }
  }
}
