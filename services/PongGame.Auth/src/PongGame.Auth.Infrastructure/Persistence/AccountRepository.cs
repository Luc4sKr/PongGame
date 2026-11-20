using Microsoft.EntityFrameworkCore;
using Npgsql;
using PongGame.Auth.Application;
using PongGame.Auth.Domain;

namespace PongGame.Auth.Infrastructure.Persistence;

internal sealed class AccountRepository(AuthDbContext db) : IAccountRepository
{
    public Task<Account?> FindAccountAsync(Username username, CancellationToken ct)
    {
        var normalized = username.Value.ToLowerInvariant();
        return db.Accounts.SingleOrDefaultAsync(a => a.Username.Value.ToLower() == normalized, ct);
    }

    public async Task<bool> TryRegisterAsync(Account account, CancellationToken ct)
    {
        db.Accounts.Add(account);
        try
        {
            await db.SaveChangesAsync(ct);
            return true;
        }
        catch (DbUpdateException ex) when (ex.InnerException is PostgresException
               { SqlState: PostgresErrorCodes.UniqueViolation, ConstraintName: "auth_users_username_idx" })
        {
            return false;
        }
    }

    public async Task<bool> TryOpenSessionAsync(RefreshSession session, CancellationToken ct)
    {
        await using var transaction = await db.Database.BeginTransactionAsync(ct);
        // Recovery must see this session if the previously verified password is still current.
        var valid = await db.Database.SqlQuery<Guid>($"""
            SELECT id AS "Value" FROM auth_users
            WHERE id = {session.AccountId} AND password_hash = {session.Account.PasswordHash} FOR SHARE
            """).AnyAsync(ct);
        if (!valid)
            return false;

        await db.Sessions.Where(s => s.AccountId == session.AccountId && s.ExpiresAt <= DateTimeOffset.UtcNow)
            .ExecuteDeleteAsync(ct);
        db.Sessions.Add(session);
        await db.SaveChangesAsync(ct);
        await transaction.CommitAsync(ct);
        return true;
    }

    public Task<RefreshSession?> FindSessionAsync(byte[] tokenHash, CancellationToken ct) =>
        db.Sessions.AsNoTracking().Include(s => s.Account).SingleOrDefaultAsync(s => s.TokenHash == tokenHash, ct);

    public async Task<bool> TryReplaceSessionAsync(RefreshSession current, RefreshSession replacement, CancellationToken ct)
    {
        var updated = await db.Sessions
            .Where(s => s.TokenHash == current.TokenHash && s.ExpiresAt > DateTimeOffset.UtcNow)
            .ExecuteUpdateAsync(set => set.SetProperty(s => s.TokenHash, replacement.TokenHash), ct);
        return updated == 1;
    }

    public async Task<bool> TrySaveRecoveryAsync(Account account, CancellationToken ct)
    {
        await using var transaction = await db.Database.BeginTransactionAsync(ct);
        try
        {
            // The recovery hash is a concurrency token. Only its first consumer can commit.
            await db.SaveChangesAsync(ct);
        }
        catch (DbUpdateConcurrencyException)
        {
            return false;
        }

        await db.Sessions.Where(s => s.AccountId == account.Id).ExecuteDeleteAsync(ct);
        await transaction.CommitAsync(ct);
        return true;
    }
}
