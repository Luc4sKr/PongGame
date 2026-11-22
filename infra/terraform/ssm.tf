resource "aws_ssm_parameter" "jwt_kms_alias" {
  name        = "/ponggame/${var.environment}/jwt/kms-key-alias"
  description = "KMS key alias used to sign and verify PongGame JWTs"
  type        = "String"
  value       = aws_kms_alias.jwt_signing.name
}

resource "aws_ssm_parameter" "jwt_issuer" {
  name        = "/ponggame/${var.environment}/jwt/issuer"
  description = "Expected JWT issuer across PongGame microservices"
  type        = "String"
  value       = var.jwt_issuer
}

resource "aws_ssm_parameter" "jwt_audience" {
  name        = "/ponggame/${var.environment}/jwt/audience"
  description = "Expected JWT audience across PongGame microservices"
  type        = "String"
  value       = var.jwt_audience
}

resource "aws_ssm_parameter" "elasticache_cluster_id" {
  name        = "/ponggame/${var.environment}/cache/cluster-id"
  description = "ElastiCache Redis cluster ID used by PongGame services"
  type        = "String"
  value       = aws_elasticache_cluster.pong_redis.cluster_id
}
