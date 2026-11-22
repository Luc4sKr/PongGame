using System.Buffers.Text;
using System.Security.Cryptography;
using System.Text;
using PongGame.Auth.Domain;

namespace PongGame.Auth.Application;

public sealed class AccountService(IAccountRepository accounts, IPasswordHasher passwords, ITokenIssuer tokens, TimeProvider clock)
{
    public async Task<RegisteredAccount?> RegisterAsync(string username, string password, CancellationToken ct)
    {
        var name = Username.Parse(username);
        var recoveryCode = NewSecret();
        var account = Account.Register(name, passwords.Hash(new Password(password)), HashSecret(recoveryCode));
        return await accounts.TryRegisterAsync(account, ct)
            ? new RegisteredAccount(account.Id, name.Value, recoveryCode)
            : null;
    }

    public async Task<TokenPair?> LoginAsync(string username, string password, CancellationToken ct)
    {
        var account = await accounts.FindAccountAsync(Username.Parse(username), ct);
        if (!passwords.Verify(new Password(password), account?.PasswordHash) || account is null)
            return null;

        var refreshToken = NewSecret();
        var session = account.OpenSession(HashSecret(refreshToken), clock.GetUtcNow());
        return await accounts.TryOpenSessionAsync(session, ct)
            ? await tokens.IssueAsync(account.Id, account.Username.Value, refreshToken, ct)
            : null;
    }

    public async Task<TokenPair?> RefreshAsync(string refreshToken, CancellationToken ct)
    {
        var current = await accounts.FindSessionAsync(HashSecret(refreshToken), ct);
        if (current is null)
            return null;

        var refresh = NewSecret();
        var replacement = current.Renew(HashSecret(refresh), clock.GetUtcNow());
        if (replacement is null || !await accounts.TryReplaceSessionAsync(current, replacement, ct))
            return null;

        return await tokens.IssueAsync(current.AccountId, current.Account.Username.Value, refresh, ct);
    }

    public async Task<RecoveryResult?> RecoverAsync(string username, string recoveryCode, string newPassword, CancellationToken ct)
    {
        var account = await accounts.FindAccountAsync(Username.Parse(username), ct);
        var passwordHash = passwords.Hash(new Password(newPassword));
        var replacement = NewSecret();
        if (account is null || !account.TryRecover(HashSecret(recoveryCode), passwordHash, HashSecret(replacement)))
            return null;

        return await accounts.TrySaveRecoveryAsync(account, ct) ? new RecoveryResult(replacement) : null;
    }

    private static string NewSecret() => Base64Url.EncodeToString(RandomNumberGenerator.GetBytes(32));
    private static byte[] HashSecret(string secret) => SHA256.HashData(Encoding.UTF8.GetBytes(secret));
}
