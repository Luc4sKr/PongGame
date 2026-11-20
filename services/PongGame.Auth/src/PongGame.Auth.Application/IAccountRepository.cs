using PongGame.Auth.Domain;

namespace PongGame.Auth.Application;

public interface IAccountRepository
{
    Task<Account?> FindAccountAsync(Username username, CancellationToken ct);
    Task<bool> TryRegisterAsync(Account account, CancellationToken ct);
    Task<bool> TryOpenSessionAsync(RefreshSession session, CancellationToken ct);
    Task<RefreshSession?> FindSessionAsync(byte[] tokenHash, CancellationToken ct);
    Task<bool> TryReplaceSessionAsync(RefreshSession current, RefreshSession replacement, CancellationToken ct);
    Task<bool> TrySaveRecoveryAsync(Account account, CancellationToken ct);
}
