using System.ComponentModel.DataAnnotations;
using PongGame.Auth.Domain;

namespace PongGame.Auth.Api;

internal static class CredentialRules
{
    public const string Username = "^" + Domain.Username.Format + "$";
    public const string Secret = "^[A-Za-z0-9_-]{43}$";
    public const int MinPasswordLength = Password.MinLength;
    public const int MaxPasswordLength = Password.MaxLength;
}

public sealed record Credentials(
    [Required, RegularExpression(CredentialRules.Username)] string Username,
    [Required, StringLength(CredentialRules.MaxPasswordLength, MinimumLength = CredentialRules.MinPasswordLength)] string Password);

public sealed record RecoveryRequest(
    [Required, RegularExpression(CredentialRules.Username)] string Username,
    [Required, RegularExpression(CredentialRules.Secret)] string RecoveryCode,
    [Required, StringLength(CredentialRules.MaxPasswordLength, MinimumLength = CredentialRules.MinPasswordLength)] string NewPassword);

public sealed record RefreshRequest([Required, RegularExpression(CredentialRules.Secret)] string RefreshToken);
