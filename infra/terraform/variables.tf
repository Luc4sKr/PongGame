variable "aws_region" {
  description = "AWS region (used for both MiniStack and real AWS)"
  type        = string
  default     = "us-east-1"
}

variable "aws_endpoint_url" {
  description = "Custom AWS endpoint URL for MiniStack (leave empty when targeting real AWS)"
  type        = string
  default     = "http://localhost:4566"
}

variable "aws_access_key" {
  description = "AWS access key (12-digit account ID or test key for MiniStack)"
  type        = string
  default     = "000000000000"
}

variable "aws_secret_key" {
  description = "AWS secret key (test key for MiniStack)"
  type        = string
  default     = "test"
  sensitive   = true
}

variable "environment" {
  description = "Deployment environment label"
  type        = string
  default     = "local"
}

variable "kms_key_alias" {
  description = "KMS alias for the asymmetric JWT signing key"
  type        = string
  default     = "alias/ponggame-jwt-signing"
}

variable "elasticache_cluster_id" {
  description = "ElastiCache Redis cluster identifier"
  type        = string
  default     = "ponggame-redis"
}

variable "elasticache_node_type" {
  description = "ElastiCache node type"
  type        = string
  default     = "cache.t3.micro"
}

variable "jwt_issuer" {
  description = "JWT issuer stored in SSM Parameter Store"
  type        = string
  default     = "PongGame.Auth"
}

variable "jwt_audience" {
  description = "JWT audience stored in SSM Parameter Store"
  type        = string
  default     = "PongGame"
}
