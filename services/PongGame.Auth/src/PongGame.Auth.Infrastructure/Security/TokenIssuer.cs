using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Amazon;
using Amazon.KeyManagementService;
using Amazon.KeyManagementService.Model;
using Amazon.Runtime;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Tokens;
using PongGame.Auth.Application;

namespace PongGame.Auth.Infrastructure.Security;

public sealed class TokenIssuer : ITokenIssuer, IDisposable
{
    private const int LifetimeSeconds = 15 * 60;
    private readonly JsonWebTokenHandler handler = new();
    private readonly ILogger<TokenIssuer> logger;
    private readonly string issuer;
    private readonly string audience;

    // Local RSA fallback (used only when Kms:KeyId is not configured, e.g., isolated dev/tests)
    private readonly SigningCredentials? localSigningCredentials;

    // AWS KMS asymmetric signing mode
    private readonly AmazonKeyManagementServiceClient? kmsClient;
    private readonly string? kmsKeyId;
    private string? kmsKid;

    public PublicKeySet PublicKeys { get; private set; } = new([]);

    public TokenIssuer(IConfiguration config, IHostEnvironment environment, ILogger<TokenIssuer> logger)
    {
        this.logger = logger;
        issuer = config["Jwt:Issuer"] ?? throw new InvalidOperationException("Configure Jwt:Issuer.");
        audience = config["Jwt:Audience"] ?? throw new InvalidOperationException("Configure Jwt:Audience.");

        var configuredKmsKeyId = config["Kms:KeyId"] ?? config["KMS_KEY_ID"];
        if (!string.IsNullOrWhiteSpace(configuredKmsKeyId))
        {
            kmsKeyId = configuredKmsKeyId;
            var region = config["AWS_REGION"] ?? config["Kms:Region"] ?? "us-east-1";
            var endpointUrl = config["AWS_ENDPOINT_URL"] ?? config["Kms:EndpointUrl"];

            if (!string.IsNullOrWhiteSpace(endpointUrl))
            {
                var accessKey = config["AWS_ACCESS_KEY_ID"] ?? "000000000000";
                var secretKey = config["AWS_SECRET_ACCESS_KEY"] ?? "test";
                var kmsConfig = new AmazonKeyManagementServiceConfig
                {
                    ServiceURL = endpointUrl,
                    AuthenticationRegion = region
                };
                kmsClient = new AmazonKeyManagementServiceClient(new BasicAWSCredentials(accessKey, secretKey), kmsConfig);
                logger.LogInformation("KMS TokenIssuer configured for endpoint {Endpoint} using key {KeyId}.", endpointUrl, kmsKeyId);
            }
            else
            {
                var kmsConfig = new AmazonKeyManagementServiceConfig
                {
                    RegionEndpoint = RegionEndpoint.GetBySystemName(region)
                };
                kmsClient = new AmazonKeyManagementServiceClient(kmsConfig);
                logger.LogInformation("KMS TokenIssuer configured for AWS region {Region} using key {KeyId}.", region, kmsKeyId);
            }

            return;
        }

        using var rsa = RSA.Create(3072);
        if (config["Jwt:PrivateKeyPem"] is { Length: > 0 } pem)
            rsa.ImportFromPem(pem);
        else if (environment.IsDevelopment())
            logger.LogWarning("Usando chave JWT temporária de desenvolvimento (sem KMS), válida até o próximo reinício.");
        else
            throw new InvalidOperationException("Configure Kms:KeyId ou Jwt:PrivateKeyPem para assinatura de JWT.");

        if (rsa.KeySize < 2048)
            throw new InvalidOperationException("A chave RSA deve ter pelo menos 2048 bits.");

        var key = new RsaSecurityKey(rsa.ExportParameters(true));
        key.KeyId = Base64UrlEncoder.Encode(key.ComputeJwkThumbprint());
        localSigningCredentials = new SigningCredentials(key, SecurityAlgorithms.RsaSha256);
        var publicKey = rsa.ExportParameters(false);
        PublicKeys = new PublicKeySet([new PublicJwk("RSA", "sig", "RS256", key.KeyId,
            Base64UrlEncoder.Encode(publicKey.Modulus!), Base64UrlEncoder.Encode(publicKey.Exponent!))]);
    }

    public async Task InitializeAsync(CancellationToken ct = default)
    {
        if (kmsClient is null || string.IsNullOrWhiteSpace(kmsKeyId))
            return;

        Exception? lastError = null;
        for (var attempt = 1; attempt <= 20; attempt++)
        {
            try
            {
                var response = await kmsClient.GetPublicKeyAsync(new GetPublicKeyRequest { KeyId = kmsKeyId }, ct);
                using var rsa = RSA.Create();
                rsa.ImportSubjectPublicKeyInfo(response.PublicKey.ToArray(), out _);
                var rsaParams = rsa.ExportParameters(false);
                var rsaKey = new RsaSecurityKey(rsaParams);
                kmsKid = Base64UrlEncoder.Encode(rsaKey.ComputeJwkThumbprint());
                PublicKeys = new PublicKeySet([new PublicJwk("RSA", "sig", "RS256", kmsKid,
                    Base64UrlEncoder.Encode(rsaParams.Modulus!), Base64UrlEncoder.Encode(rsaParams.Exponent!))]);
                logger.LogInformation("Loaded asymmetric RSA public key from KMS key {KeyId} (kid={Kid}).", kmsKeyId, kmsKid);
                return;
            }
            catch (Exception ex) when (!ct.IsCancellationRequested)
            {
                lastError = ex;
                logger.LogWarning(ex, "Waiting for KMS key {KeyId} (attempt {Attempt}/20)...", kmsKeyId, attempt);
                await Task.Delay(TimeSpan.FromSeconds(1), ct);
            }
        }

        throw new InvalidOperationException($"Failed to initialize KMS key '{kmsKeyId}'.", lastError);
    }

    public async Task<TokenPair> IssueAsync(Guid userId, string username, string refreshToken, CancellationToken ct = default)
    {
        var now = DateTimeOffset.UtcNow;

        if (kmsClient is not null && !string.IsNullOrWhiteSpace(kmsKeyId))
        {
            if (kmsKid is null)
                await InitializeAsync(ct);

            var headerJson = JsonSerializer.SerializeToUtf8Bytes(new Dictionary<string, object>
            {
                ["alg"] = "RS256",
                ["typ"] = "JWT",
                ["kid"] = kmsKid!
            });

            var iat = now.ToUnixTimeSeconds();
            var exp = now.AddSeconds(LifetimeSeconds).ToUnixTimeSeconds();
            var payloadJson = JsonSerializer.SerializeToUtf8Bytes(new Dictionary<string, object>
            {
                ["iss"] = issuer,
                ["aud"] = audience,
                ["sub"] = userId.ToString(),
                ["preferred_username"] = username,
                ["jti"] = Guid.NewGuid().ToString(),
                ["iat"] = iat,
                ["nbf"] = iat,
                ["exp"] = exp
            });

            var signingInput = $"{Base64UrlEncoder.Encode(headerJson)}.{Base64UrlEncoder.Encode(payloadJson)}";
            using var messageStream = new MemoryStream(Encoding.UTF8.GetBytes(signingInput));
            var signResponse = await kmsClient.SignAsync(new SignRequest
            {
                KeyId = kmsKeyId,
                Message = messageStream,
                MessageType = MessageType.RAW,
                SigningAlgorithm = SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256
            }, ct);

            var signatureB64 = Base64UrlEncoder.Encode(signResponse.Signature.ToArray());
            return new TokenPair($"{signingInput}.{signatureB64}", "Bearer", LifetimeSeconds, refreshToken);
        }

        var utcNow = now.UtcDateTime;
        var token = handler.CreateToken(new SecurityTokenDescriptor
        {
            Issuer = issuer,
            Audience = audience,
            IssuedAt = utcNow,
            NotBefore = utcNow,
            Expires = utcNow.AddSeconds(LifetimeSeconds),
            Claims = new Dictionary<string, object>
            {
                ["sub"] = userId.ToString(),
                ["preferred_username"] = username,
                ["jti"] = Guid.NewGuid().ToString()
            },
            SigningCredentials = localSigningCredentials
        });
        return new TokenPair(token, "Bearer", LifetimeSeconds, refreshToken);
    }

    public void Dispose() => kmsClient?.Dispose();
}

public sealed record PublicJwk(string Kty, string Use, string Alg, string Kid, string N, string E);
public sealed record PublicKeySet(PublicJwk[] Keys);
