resource "aws_kms_key" "jwt_signing" {
  description              = "PongGame asymmetric RSA key for JWT signing (Auth Service) and public-key verification (Player, Matchmaking, Game Services)"
  customer_master_key_spec = "RSA_2048"
  key_usage                = "SIGN_VERIFY"
  deletion_window_in_days  = 7

  tags = {
    Project     = "PongGame"
    Environment = var.environment
    Purpose     = "jwt-asymmetric-signing"
  }
}

resource "aws_kms_alias" "jwt_signing" {
  name          = var.kms_key_alias
  target_key_id = aws_kms_key.jwt_signing.key_id
}
