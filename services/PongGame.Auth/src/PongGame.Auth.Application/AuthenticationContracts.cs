using PongGame.Auth.Domain;

namespace PongGame.Auth.Application;

public interface IPasswordHasher
{
    string Hash(Password password);
    bool Verify(Password password, string? storedHash);
}

public interface ITokenIssuer
{
    TokenPair Issue(Guid userId, string username, string refreshToken);
}

public sealed record RegisteredAccount(Guid UserId, string Username, string RecoveryCode);
public sealed record RecoveryResult(string RecoveryCode);
public sealed record TokenPair(string AccessToken, string TokenType, int ExpiresIn, string RefreshToken);
