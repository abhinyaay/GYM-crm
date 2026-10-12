# Application secrets live in Secrets Manager and are injected into tasks by ECS at start-up.
# Nothing sensitive is baked into images, task definitions or the repository.

resource "random_password" "jwt" {
  length  = 64
  special = false
}

resource "random_password" "access_master" {
  length  = 64
  special = false
}

resource "random_password" "simulated_gateway" {
  length  = 48
  special = false
}

resource "random_password" "analytics_db" {
  length  = 40
  special = false
}

resource "aws_secretsmanager_secret" "app" {
  name                    = "${local.name}/app"
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id = aws_secretsmanager_secret.app.id
  secret_string = jsonencode({
    JWT_SECRET               = random_password.jwt.result
    SIMULATED_GATEWAY_SECRET = random_password.simulated_gateway.result
    ACCESS_MASTER_KEY        = random_password.access_master.result
    REDIS_PASSWORD           = random_password.redis_auth.result
    ANALYTICS_DB_PASSWORD    = random_password.analytics_db.result
    ANALYTICS_DATABASE_URL = format("postgresql+psycopg://gym_analytics_ro:%s@%s:5432/gymops?sslmode=require",
    random_password.analytics_db.result, aws_db_instance.postgres.address)
  })
}

# Bootstrap admin password: set once out-of-band (`aws secretsmanager put-secret-value`), never in Terraform state.
resource "aws_secretsmanager_secret" "bootstrap_admin" {
  name                    = "${local.name}/bootstrap-admin-password"
  recovery_window_in_days = 7
}

# Razorpay API keys and webhook secret ({"key_id": ..., "key_secret": ..., "webhook_secret": ...}): set out-of-band
# from the Razorpay dashboard (`aws secretsmanager put-secret-value`), never in Terraform state.
resource "aws_secretsmanager_secret" "razorpay" {
  name                    = "${local.name}/razorpay"
  recovery_window_in_days = 7
}

# SMTP credentials ({"username": ..., "password": ...}, e.g. SES SMTP credentials): set out-of-band, never in state.
resource "aws_secretsmanager_secret" "smtp" {
  name                    = "${local.name}/smtp"
  recovery_window_in_days = 7
}

# SMS provider credentials, set out-of-band: Twilio {"account_sid": ..., "auth_token": ...} or MSG91 {"auth_key": ...}.
resource "aws_secretsmanager_secret" "sms" {
  name                    = "${local.name}/sms"
  recovery_window_in_days = 7
}
