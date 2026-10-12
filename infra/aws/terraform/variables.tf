variable "region" {
  type    = string
  default = "ap-south-1"
}

variable "environment" {
  type    = string
  default = "prod"
}

variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

variable "az_count" {
  type    = number
  default = 2
}

variable "single_nat_gateway" {
  description = "One NAT gateway for all AZs (cheaper, less resilient). Use false for production."
  type        = bool
  default     = false
}

variable "acm_certificate_arn" {
  description = "ACM certificate for the HTTPS listener. Empty = HTTP only (non-production)."
  type        = string
  default     = ""
}

variable "image_tag" {
  description = "Image tag deployed by the pipeline (git SHA)."
  type        = string
  default     = "latest"
}

variable "core_desired_count" {
  type    = number
  default = 2
}

variable "core_max_count" {
  type    = number
  default = 6
}

variable "analytics_desired_count" {
  type    = number
  default = 1
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.medium"
}

variable "db_multi_az" {
  type    = bool
  default = true
}

variable "redis_node_type" {
  type    = string
  default = "cache.t4g.small"
}

variable "alarm_email" {
  description = "E-mail subscribed to CloudWatch alarm notifications (optional)."
  type        = string
  default     = ""
}

variable "bootstrap_admin_email" {
  type    = string
  default = ""
}

variable "payment_gateway" {
  description = "simulated (no real money) or razorpay. With razorpay, put key_id / key_secret / webhook_secret into the <name>/razorpay secret first."
  type        = string
  default     = "simulated"
  validation {
    condition     = contains(["simulated", "razorpay"], var.payment_gateway)
    error_message = "payment_gateway must be simulated or razorpay."
  }
}

variable "public_base_url" {
  description = "Public URL of the web app (https://<domain>), used for preference / unsubscribe links in e-mails."
  type        = string
  default     = "http://localhost"
}

variable "email_provider" {
  description = "log (no e-mail) or smtp. With smtp, put {\"username\", \"password\"} (e.g. SES SMTP credentials) into the <name>/smtp secret first."
  type        = string
  default     = "log"
  validation {
    condition     = contains(["log", "smtp"], var.email_provider)
    error_message = "email_provider must be log or smtp."
  }
}

variable "smtp_host" {
  description = "SMTP endpoint, e.g. email-smtp.ap-south-1.amazonaws.com (SES) or smtp.sendgrid.net."
  type        = string
  default     = ""
}

variable "smtp_port" {
  type    = number
  default = 587
}

variable "mail_from_address" {
  description = "Sender address; must be a verified SES identity / SendGrid sender."
  type        = string
  default     = "no-reply@gymops.local"
}

variable "mail_from_name" {
  type    = string
  default = "GymOps"
}

variable "sms_provider" {
  description = "log (no SMS), twilio or msg91. Put the provider credentials into the <name>/sms secret first (twilio: account_sid, auth_token; msg91: auth_key)."
  type        = string
  default     = "log"
  validation {
    condition     = contains(["log", "twilio", "msg91"], var.sms_provider)
    error_message = "sms_provider must be log, twilio or msg91."
  }
}

variable "sms_sender" {
  description = "Non-secret sender settings: twilio_from_number / twilio_messaging_service_sid / msg91_sender_id / msg91_template_id / msg91_dlt_template_id."
  type        = map(string)
  default     = {}
}
