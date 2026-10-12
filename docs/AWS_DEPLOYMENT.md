# AWS deployment guide

The infrastructure is defined in [`infra/aws/terraform`](../infra/aws/terraform). It passes `terraform validate` and `terraform fmt -check` (`make tf-validate`). It has **not** yet been applied to a real AWS account from this repository, so treat the first apply as a reviewed rollout.

## Target architecture

| Concern | Service | Notes |
|---|---|---|
| Compute | **ECS on Fargate** | `core-service`: 1 vCPU / 2 GB, 2 to 6 tasks. `analytics-service`: 0.5 vCPU / 1 GB, 1 task or more. `web-service` (static React app): 0.25 vCPU / 0.5 GB, 2 tasks. No servers to patch. |
| Images | **ECR** | One repository per service. Tags are immutable (the git SHA), images are scanned on push, and only the last 30 are kept. |
| Load balancing | **ALB** | HTTPS with a TLS 1.3 policy and an HTTP→HTTPS redirect. Path rules: `/analytics/*` goes to analytics; `/api/*`, `/actuator/health*` and Swagger go to core; everything else goes to the web app. `/actuator/prometheus` and `/metrics` return a fixed 404. `drop_invalid_header_fields` is on. |
| Service-to-service | **Cloud Map** | Core calls `http://analytics.gymops.internal:8000` over private DNS, so the traffic never leaves the VPC. |
| Database | **RDS PostgreSQL 16** | Multi-AZ, gp3 with storage autoscaling, encryption at rest, `rds.force_ssl=1`, 7-day backups with point-in-time recovery, Performance Insights, slow-query log over 500 ms, deletion protection, final snapshot. |
| Cache | **ElastiCache Redis 7** | Primary plus replica with automatic failover, Multi-AZ, encryption in transit (TLS) and at rest, AUTH token. |
| Secrets | **Secrets Manager** | RDS master password is AWS-managed and rotatable. JWT secret, Redis AUTH token, analytics DB password and the analytics `DATABASE_URL` are injected by ECS as environment variables at task start. The bootstrap admin password is set out of band. |
| Logs | **CloudWatch Logs** | `/gymops/<env>/<service>`, kept 30 days. JSON log lines can be queried with Logs Insights. |
| Metrics / alarms | **CloudWatch + Container Insights** | Alarms go to an SNS topic, optionally with an email subscription. |
| CI access | **IAM OIDC** | GitHub Actions assumes `gymops-<env>-github-deploy`, limited to `main` and the `production` environment. It may push to the two ECR repositories, register task definitions, update services, and pass only the two task roles. |

## Networking

- **VPC** `10.20.0.0/16` across 2 Availability Zones.
- **Public subnets** (`10.20.0.0/24`, `10.20.1.0/24`): ALB and NAT gateways only. No task gets a public IP.
- **Private app subnets** (`10.20.10.0/24`, `.11`): ECS tasks. Outbound internet goes through NAT, one per AZ by default; `single_nat_gateway = true` saves cost outside production.
- **Private data subnets** (`10.20.20.0/24`, `.21`): RDS and ElastiCache. Their route table has **no internet route**.

## Security groups

Each rule references a security group, not a CIDR, except the public ALB listener:

| SG | Inbound | From |
|---|---|---|
| `alb` | 443, 80 | 0.0.0.0/0 |
| `web` | 8080 | `alb` |
| `core` | 8080 | `alb` |
| `analytics` | 8000 | `alb`, `core` |
| `postgres` | 5432 | `core`, `analytics` |
| `redis` | 6379 | `core` (analytics does not use Redis) |

## Application configuration on ECS

| Variable | Source |
|---|---|
| `DB_URL`, `DB_USERNAME`, `REDIS_HOST`, `REDIS_SSL=true`, `ANALYTICS_BASE_URL`, `SPRING_PROFILES_ACTIVE=prod` (JSON logs) | Plain environment variables in the task definition |
| `DB_PASSWORD` | `<rds-managed-secret>:password::` |
| `JWT_SECRET`, `REDIS_PASSWORD`, `SIMULATED_GATEWAY_SECRET` | `gymops-<env>/app` JSON keys |
| `PAYMENT_GATEWAY` | Terraform variable `payment_gateway` (`simulated` by default) |
| `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` | `gymops-<env>/razorpay` JSON keys `key_id`, `key_secret`, `webhook_secret`. Injected only when `payment_gateway = "razorpay"`. Set out of band, never in Terraform state. |
| `EMAIL_PROVIDER`, `SMTP_HOST`, `SMTP_PORT`, `MAIL_FROM_ADDRESS`, `MAIL_FROM_NAME`, `PUBLIC_BASE_URL` | Terraform variables `email_provider` (`log` by default), `smtp_host`, `smtp_port`, `mail_from_address`, `mail_from_name`, `public_base_url`. `SMTP_STARTTLS_REQUIRED=true`. |
| `SMTP_USERNAME`, `SMTP_PASSWORD` | `gymops-<env>/smtp` JSON keys `username`, `password` (e.g. SES SMTP credentials). Injected only when `email_provider = "smtp"`. Set out of band. |
| `SMS_PROVIDER`, `TWILIO_FROM_NUMBER` / `TWILIO_MESSAGING_SERVICE_SID`, `MSG91_SENDER_ID`, `MSG91_TEMPLATE_ID`, `MSG91_DLT_TEMPLATE_ID` | Terraform variables `sms_provider` (`log` by default) and `sms_sender` (map of the non-secret sender settings) |
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` or `MSG91_AUTH_KEY` | `gymops-<env>/sms` JSON keys `account_sid`, `auth_token` (Twilio) or `auth_key` (MSG91). Injected only for the selected provider. Set out of band. |
| Analytics `DATABASE_URL`, `JWT_SECRET` | `gymops-<env>/app` JSON keys |
| `DEMO_DATA_ENABLED` | Forced to `false` |

**Health checks:**
- The ALB checks `/actuator/health/readiness` on core, which includes the database only, and `/health/ready` on analytics.
- The container health check uses liveness.
- Services use a deployment circuit breaker with automatic rollback, keep minimum healthy at 100% and allow up to 200% during a rolling deploy.

**Auto scaling (core):** target tracking on average CPU 60% and on ALB requests per target (500 per minute), between 2 and 6 tasks.

## First-time rollout

1. **Remote state.** Create an S3 bucket (versioning plus encryption) and a DynamoDB lock table. Then run:

   ```bash
   terraform init -backend-config="bucket=<state-bucket>" -backend-config="key=gymops/prod.tfstate" \
     -backend-config="region=ap-south-1" -backend-config="dynamodb_table=<lock-table>"
   ```

2. **Variables.** Copy `terraform.tfvars.example` to `terraform.tfvars` and set the ACM certificate, GitHub repository, alarm email and bootstrap admin email.

3. **Images.** Push an initial image to both ECR repositories, or run the pipeline once with `-target` for the repositories.

4. **Apply.**

   ```bash
   terraform apply -var image_tag=<sha>
   ```

5. **Bootstrap admin password** (stored outside Terraform state):

   ```bash
   aws secretsmanager put-secret-value --secret-id gymops-prod/bootstrap-admin-password \
     --secret-string '<strong password>'
   ```

6. **Payments (optional).** To take real payments:
   - Store the Razorpay keys:

     ```bash
     aws secretsmanager put-secret-value --secret-id gymops-prod/razorpay \
       --secret-string '{"key_id":"…","key_secret":"…","webhook_secret":"…"}'
     ```
   - Apply with `-var payment_gateway=razorpay`.
   - In the Razorpay dashboard, add the webhook `https://<domain>/api/v1/webhooks/razorpay`, subscribed to `payment.captured`, `payment.failed` and `token.*`.

7. **Analytics read-only role** (one-off). Run `infra/aws/scripts/create-analytics-role.sh` from inside the VPC, for example through `aws ecs execute-command` into a core task. Flyway runs each migration only once, so if the role is created after the first deploy, run the `GRANT` / `REVOKE` statements from `V3__analytics_read_access.sql` and `V7__analytics_read_access_v2.sql` manually.

8. **GitHub configuration.** In the repository, set the variables `AWS_DEPLOY_ROLE_ARN` (Terraform output `github_deploy_role_arn`) and `PUBLIC_BASE_URL`. Create a protected `production` environment with required reviewers.

9. **DNS.** Point a Route 53 alias record at `alb_dns_name`.

## E-mail and SMS (optional)

Without these steps the app runs with the `log` providers: nothing is sent and messages are only logged.

1. **E-mail through Amazon SES.**
   - Verify the sending domain in SES (DKIM plus a custom MAIL FROM) and request production access.
   - Create SMTP credentials in SES.
   - Store them in the secret:

     ```bash
     aws secretsmanager put-secret-value --secret-id gymops-prod/smtp \
       --secret-string '{"username":"…","password":"…"}'
     ```
   - Apply with `-var email_provider=smtp -var smtp_host=email-smtp.ap-south-1.amazonaws.com -var mail_from_address=no-reply@<domain> -var public_base_url=https://<domain>`.
   - SendGrid works the same way: use `smtp.sendgrid.net`, with user `apikey` and the API key as the password.
2. **SMS.**
   - Store `{"account_sid":"…","auth_token":"…"}` (Twilio) or `{"auth_key":"…"}` (MSG91) in `gymops-prod/sms`.
   - Apply with `-var sms_provider=twilio` or `msg91`, plus `-var 'sms_sender={twilio_from_number="+1…"}'`. For MSG91 use `{msg91_sender_id="…", msg91_template_id="…", msg91_dlt_template_id="…"}`.
   - Indian traffic needs DLT-registered templates (MSG91).
3. **Verify.** Open **Messaging → Send test message** as an admin. It shows the active providers, whether they are configured, and the delivery result with the provider message id.
4. **SMTP failures are isolated.** The mail health indicator is disabled, so an SMTP outage never fails readiness. Failed messages are retried and stay visible as FAILED in the messaging centre, where they can be resent.

## Deployments

Every push to `main` runs the pipeline:

- **Test and build.** Tests, static analysis, image build and an end-to-end smoke test of the built images.
- **Push.** Images go to ECR.
- **Approval.** A person approves the `production` deployment.
- **Roll out.** Register task definitions with the new image, run `ecs update-service`, then `ecs wait services-stable`.
- **Verify.** `scripts/post_deploy_check.sh`.

Flyway migrations run on core start-up, before the instance reports ready. Migrations must stay backward-compatible (expand, then contract), because old and new tasks run side by side during a rolling deploy.

**Rollback:**
- The ECS circuit breaker reverts automatically when new tasks fail their health checks.
- To roll back manually, `aws ecs update-service --task-definition <previous revision>`. Images are immutable, so every previous revision is still runnable.

## Monitoring and alerting

| Alarm | Condition |
|---|---|
| `alb_5xx` | More than 20 target 5xx responses in 5 minutes, for 2 consecutive periods |
| `core_unhealthy` | Any unhealthy core target |
| `core_p95_latency` | p95 target response time above 1.5 s |
| `rds_cpu` | Average above 80% |
| `rds_storage` | Free storage below 5 GB |
| `redis_memory` | Above 80% |
| `core-error-logs` | More than 10 `"level":"ERROR"` log lines in 5 minutes (log metric filter) |

**Dashboards and tracing:** Container Insights covers task CPU and memory. Application metrics are exposed at `/actuator/prometheus` inside the VPC, ready for Amazon Managed Prometheus or Grafana; that scraper is *not* provisioned here. To trace a request, search CloudWatch Logs Insights by `correlationId`, which is shared by the ALB/Nginx request ID, core logs and analytics logs.

## Cost notes (rough guidance, not a quote)

The main cost drivers are:
- the two NAT gateways (or one with `single_nat_gateway`);
- RDS Multi-AZ;
- the ElastiCache primary and replica;
- 3 or more Fargate tasks;
- the ALB.

For staging, set `db_multi_az=false`, `single_nat_gateway=true`, a smaller `redis_node_type` and `core_desired_count=1`.
