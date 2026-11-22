FROM hashicorp/terraform:1.11

WORKDIR /terraform
COPY versions.tf variables.tf providers.tf kms.tf elasticache.tf ssm.tf outputs.tf ./
COPY apply.sh /usr/local/bin/apply.sh
RUN sed -i 's/\r$//' /usr/local/bin/apply.sh && chmod +x /usr/local/bin/apply.sh && terraform init -no-color

ENTRYPOINT ["/bin/sh", "/usr/local/bin/apply.sh"]
