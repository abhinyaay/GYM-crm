resource "aws_lb" "main" {
  name                       = local.name
  load_balancer_type         = "application"
  internal                   = false
  security_groups            = [aws_security_group.alb.id]
  subnets                    = aws_subnet.public[*].id
  drop_invalid_header_fields = true
  idle_timeout               = 60
}

resource "aws_lb_target_group" "core" {
  name                 = "${local.name}-core"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 30
  health_check {
    path                = "/actuator/health/readiness" # DB only: Redis/analytics outages do not drain instances
    matcher             = "200"
    interval            = 15
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_target_group" "web" {
  name                 = "${local.name}-web"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 15
  health_check {
    path                = "/healthz"
    matcher             = "200"
    interval            = 15
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_target_group" "analytics" {
  name                 = "${local.name}-analytics"
  port                 = 8000
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 30
  health_check {
    path                = "/health/ready"
    matcher             = "200"
    interval            = 30
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

locals {
  https_enabled = var.acm_certificate_arn != ""
}

resource "aws_lb_listener" "https" {
  count             = local.https_enabled ? 1 : 0
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.acm_certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.web.arn # the SPA; API paths are routed by rules below
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type = local.https_enabled ? "redirect" : "forward"
    dynamic "redirect" {
      for_each = local.https_enabled ? [1] : []
      content {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }
    target_group_arn = local.https_enabled ? null : aws_lb_target_group.web.arn
  }
}

locals {
  public_listener_arn = local.https_enabled ? aws_lb_listener.https[0].arn : aws_lb_listener.http.arn
}

resource "aws_lb_listener_rule" "analytics" {
  listener_arn = local.public_listener_arn
  priority     = 10
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.analytics.arn
  }
  condition {
    path_pattern { values = ["/analytics/*"] }
  }
}

resource "aws_lb_listener_rule" "core_api" {
  listener_arn = local.public_listener_arn
  priority     = 20
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.core.arn
  }
  condition {
    path_pattern { values = ["/api/*", "/actuator/health*", "/swagger-ui*", "/v3/api-docs*"] }
  }
}

# Metrics endpoints are scraped inside the VPC only.
resource "aws_lb_listener_rule" "block_actuator" {
  listener_arn = local.public_listener_arn
  priority     = 5
  action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Not found"
      status_code  = "404"
    }
  }
  condition {
    path_pattern { values = ["/actuator/prometheus", "/actuator/metrics*", "/metrics"] }
  }
}
