package com.ponggame.matchmaking.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.kms.KmsClient;

import java.net.URI;

@Configuration
public class AwsClientsConfig {

    @Value("${app.aws.region:us-east-1}")
    private String awsRegion;

    @Value("${app.aws.endpoint-url:}")
    private String awsEndpointUrl;

    @Value("${app.aws.access-key:000000000000}")
    private String awsAccessKey;

    @Value("${app.aws.secret-key:test}")
    private String awsSecretKey;

    @Bean
    public KmsClient kmsClient() {
        var builder = KmsClient.builder().region(Region.of(awsRegion));
        if (awsEndpointUrl != null && !awsEndpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(awsEndpointUrl))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(awsAccessKey, awsSecretKey)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }

    @Bean
    public ElastiCacheClient elastiCacheClient() {
        var builder = ElastiCacheClient.builder().region(Region.of(awsRegion));
        if (awsEndpointUrl != null && !awsEndpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(awsEndpointUrl))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(awsAccessKey, awsSecretKey)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }
}
