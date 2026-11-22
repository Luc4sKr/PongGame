using PongGame.Auth.Domain;

namespace PongGame.Auth.Application;

public interface IPasswordHasher
{
    string Hash(Password password);
    bool Verify(Password password, string? storedHash);
}

public interface ITokenIssuer
{
    Task<TokenPair> IssueAsync(Guid userId, string username, string refreshToken, CancellationToken ct = default);
}

public sealed record RegisteredAccount(Guid UserId, string Username, string RecoveryCode);
public sealed record RecoveryResult(string RecoveryCode);
public sealed record TokenPair(string AccessToken, string TokenType, int ExpiresIn, string RefreshToken);
