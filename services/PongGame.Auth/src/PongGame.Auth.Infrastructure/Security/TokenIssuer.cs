using System.Security.Cryptography;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Tokens;
using PongGame.Auth.Application;

namespace PongGame.Auth.Infrastructure.Security;

public sealed class TokenIssuer : ITokenIssuer
{
    private const int LifetimeSeconds = 15 * 60;
    private readonly SigningCredentials signingCredentials;
    private readonly JsonWebTokenHandler handler = new();
    private readonly string issuer;
    private readonly string audience;

    public PublicKeySet PublicKeys { get; }

    public TokenIssuer(IConfiguration config, IHostEnvironment environment, ILogger<TokenIssuer> logger)
    {
        issuer = config["Jwt:Issuer"] ?? throw new InvalidOperationException("Configure Jwt:Issuer.");
        audience = config["Jwt:Audience"] ?? throw new InvalidOperationException("Configure Jwt:Audience.");
        using var rsa = RSA.Create(3072);
        if (config["Jwt:PrivateKeyPem"] is { Length: > 0 } pem)
            rsa.ImportFromPem(pem);
        else if (environment.IsDevelopment())
            logger.LogWarning("Usando chave JWT temporária de desenvolvimento, válida até o próximo reinício.");
        else
            throw new InvalidOperationException("Configure Jwt:PrivateKeyPem com a chave privada RSA do Auth.");

        if (rsa.KeySize < 2048)
            throw new InvalidOperationException("A chave RSA deve ter pelo menos 2048 bits.");
        var key = new RsaSecurityKey(rsa.ExportParameters(true));
        key.KeyId = Base64UrlEncoder.Encode(key.ComputeJwkThumbprint());
        signingCredentials = new SigningCredentials(key, SecurityAlgorithms.RsaSha256);
        var publicKey = rsa.ExportParameters(false);
        PublicKeys = new PublicKeySet([new PublicJwk("RSA", "sig", "RS256", key.KeyId,
            Base64UrlEncoder.Encode(publicKey.Modulus!), Base64UrlEncoder.Encode(publicKey.Exponent!))]);
    }

    public TokenPair Issue(Guid userId, string username, string refreshToken)
    {
        var now = DateTime.UtcNow;
        var token = handler.CreateToken(new SecurityTokenDescriptor
        {
            Issuer = issuer,
            Audience = audience,
            IssuedAt = now,
            NotBefore = now,
            Expires = now.AddSeconds(LifetimeSeconds),
            Claims = new Dictionary<string, object>
            {
                ["sub"] = userId.ToString(),
                ["preferred_username"] = username,
                ["jti"] = Guid.NewGuid().ToString()
            },
            SigningCredentials = signingCredentials
        });
        return new TokenPair(token, "Bearer", LifetimeSeconds, refreshToken);
    }
}

public sealed record PublicJwk(string Kty, string Use, string Alg, string Kid, string N, string E);
public sealed record PublicKeySet(PublicJwk[] Keys);
