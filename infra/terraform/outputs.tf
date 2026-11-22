output "kms_key_id" {
  description = "ID of the asymmetric KMS key for JWT signing"
  value       = aws_kms_key.jwt_signing.key_id
}

output "kms_key_arn" {
  description = "ARN of the asymmetric KMS key for JWT signing"
  value       = aws_kms_key.jwt_signing.arn
}

output "kms_key_alias" {
  description = "Alias of the asymmetric KMS key"
  value       = aws_kms_alias.jwt_signing.name
}

output "elasticache_cluster_id" {
  description = "ElastiCache Redis cluster ID"
  value       = aws_elasticache_cluster.pong_redis.cluster_id
}

output "elasticache_endpoint_address" {
  description = "ElastiCache Redis primary node address"
  value       = aws_elasticache_cluster.pong_redis.cache_nodes[0].address
}

output "elasticache_endpoint_port" {
  description = "ElastiCache Redis primary node port"
  value       = aws_elasticache_cluster.pong_redis.cache_nodes[0].port
}
