resource "aws_elasticache_cluster" "pong_redis" {
  cluster_id           = var.elasticache_cluster_id
  engine               = "redis"
  node_type            = var.elasticache_node_type
  num_cache_nodes      = 1
  parameter_group_name = "default.redis7"
  port                 = 6379

  tags = {
    Project     = "PongGame"
    Environment = var.environment
    Purpose     = "matchmaking-and-game-distributed-memory"
  }
}
