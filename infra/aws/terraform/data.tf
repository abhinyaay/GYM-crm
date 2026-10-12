# ---------------------------------------------------------------- PostgreSQL (RDS)

resource "aws_db_subnet_group" "main" {
  name       = "${local.name}-db"
  subnet_ids = aws_subnet.data[*].id
}

resource "aws_db_parameter_group" "postgres" {
  name   = "${local.name}-pg16"
  family = "postgres16"
  parameter {
    name  = "log_min_duration_statement"
    value = "500" # log queries slower than 500 ms
  }
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
}

resource "aws_db_instance" "postgres" {
  identifier                      = "${local.name}-postgres"
  engine                          = "postgres"
  engine_version                  = "16"
  instance_class                  = var.db_instance_class
  allocated_storage               = 50
  max_allocated_storage           = 500
  storage_type                    = "gp3"
  storage_encrypted               = true
  db_name                         = "gymops"
  username                        = "gymops"
  manage_master_user_password     = true # password generated and rotated by Secrets Manager
  multi_az                        = var.db_multi_az
  db_subnet_group_name            = aws_db_subnet_group.main.name
  vpc_security_group_ids          = [aws_security_group.db.id]
  parameter_group_name            = aws_db_parameter_group.postgres.name
  backup_retention_period         = 7
  backup_window                   = "20:00-21:00" # 01:30-02:30 IST
  maintenance_window              = "sun:21:00-sun:22:00"
  deletion_protection             = true
  skip_final_snapshot             = false
  final_snapshot_identifier       = "${local.name}-postgres-final"
  performance_insights_enabled    = true
  enabled_cloudwatch_logs_exports = ["postgresql"]
  auto_minor_version_upgrade      = true
  publicly_accessible             = false
}

# ---------------------------------------------------------------- Redis (ElastiCache)

resource "aws_elasticache_subnet_group" "main" {
  name       = "${local.name}-redis"
  subnet_ids = aws_subnet.data[*].id
}

resource "random_password" "redis_auth" {
  length  = 48
  special = false
}

resource "aws_elasticache_replication_group" "redis" {
  replication_group_id       = "${local.name}-redis"
  description                = "GymOps cache and rate limiting"
  engine                     = "redis"
  engine_version             = "7.1"
  node_type                  = var.redis_node_type
  num_cache_clusters         = 2
  automatic_failover_enabled = true
  multi_az_enabled           = true
  port                       = 6379
  subnet_group_name          = aws_elasticache_subnet_group.main.name
  security_group_ids         = [aws_security_group.redis.id]
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  auth_token                 = random_password.redis_auth.result
  parameter_group_name       = "default.redis7"
  snapshot_retention_limit   = 1
}
