using System.Security.Cryptography;

namespace PongGame.Auth.Domain;

public sealed class Account
{
    private byte[] recoveryHash = [];
    public Guid Id { get; private set; }
    public Username Username { get; private set; } = null!;
    public string PasswordHash { get; private set; } = null!;
    public byte[] RecoveryHash
    {
        get => recoveryHash.ToArray();
        private set => recoveryHash = value.ToArray();
    }

    private Account() { }

    public static Account Register(Username username, string passwordHash, byte[] recoveryHash)
    {
        ArgumentNullException.ThrowIfNull(username);
        ArgumentException.ThrowIfNullOrWhiteSpace(passwordHash);
        ArgumentOutOfRangeException.ThrowIfZero(recoveryHash.Length);
        return new Account
        {
            Id = Guid.NewGuid(),
            Username = username,
            PasswordHash = passwordHash,
            RecoveryHash = recoveryHash
        };
    }

    public RefreshSession OpenSession(byte[] tokenHash, DateTimeOffset now) =>
        new(this, tokenHash, now.AddDays(30));

    public bool TryRecover(byte[] presentedHash, string newPasswordHash, byte[] newRecoveryHash)
    {
        if (!CryptographicOperations.FixedTimeEquals(recoveryHash, presentedHash))
            return false;

        ArgumentException.ThrowIfNullOrWhiteSpace(newPasswordHash);
        ArgumentOutOfRangeException.ThrowIfZero(newRecoveryHash.Length);
        PasswordHash = newPasswordHash;
        RecoveryHash = newRecoveryHash;
        return true;
    }
}
