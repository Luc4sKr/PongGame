locals {
  use_local_endpoint = var.aws_endpoint_url != ""
}

provider "aws" {
  region                      = var.aws_region
  access_key                  = local.use_local_endpoint ? var.aws_access_key : null
  secret_key                  = local.use_local_endpoint ? var.aws_secret_key : null
  skip_credentials_validation = local.use_local_endpoint
  skip_metadata_api_check     = local.use_local_endpoint
  skip_requesting_account_id  = local.use_local_endpoint

  dynamic "endpoints" {
    for_each = local.use_local_endpoint ? [1] : []
    content {
      kms         = var.aws_endpoint_url
      elasticache = var.aws_endpoint_url
      ssm         = var.aws_endpoint_url
      sts         = var.aws_endpoint_url
      iam         = var.aws_endpoint_url
      ec2         = var.aws_endpoint_url
    }
  }
}
