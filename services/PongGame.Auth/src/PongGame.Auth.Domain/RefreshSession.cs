namespace PongGame.Auth.Domain;

public sealed class RefreshSession
{
    private byte[] tokenHash = [];
    public byte[] TokenHash
    {
        get => tokenHash.ToArray();
        private set => tokenHash = value.ToArray();
    }
    public Account Account { get; private set; } = null!;
    public Guid AccountId { get; private set; }
    public DateTimeOffset ExpiresAt { get; private set; }

    private RefreshSession() { }

    internal RefreshSession(Account account, byte[] tokenHash, DateTimeOffset expiresAt)
    {
        ArgumentOutOfRangeException.ThrowIfZero(tokenHash.Length);
        Account = account;
        AccountId = account.Id;
        TokenHash = tokenHash;
        ExpiresAt = expiresAt;
    }

    public RefreshSession? Renew(byte[] tokenHash, DateTimeOffset now) =>
        now < ExpiresAt ? new RefreshSession(Account, tokenHash, ExpiresAt) : null;
}
