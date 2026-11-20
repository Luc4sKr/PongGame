using Xunit;

namespace PongGame.Auth.Domain.Tests;

public sealed class AccountTests
{
    [Fact]
    public void UsernamePreservesSpellingAndComparesIgnoringCase()
    {
        var name = Username.Parse("Pong_Player");
        var alias = Username.Parse("pong_player");

        Assert.Equal("Pong_Player", name.Value);
        Assert.Equal(name, alias);
        Assert.Equal(name.GetHashCode(), alias.GetHashCode());
        Assert.Throws<ArgumentException>(() => Username.Parse("Pong_Player\n"));
    }

    [Fact]
    public void OnlyTheCurrentRecoveryCodeCanChangeThePassword()
    {
        var account = Account.Register(Username.Parse("Player"), "original-hash", [1]);

        Assert.False(account.TryRecover([9], "other-hash", [2]));
        Assert.Equal("original-hash", account.PasswordHash);
        Assert.True(account.TryRecover([1], "new-hash", [2]));
        Assert.False(account.TryRecover([1], "other-hash", [3]));
        Assert.Equal("new-hash", account.PasswordHash);
        Assert.True(account.TryRecover([2], "final-hash", [3]));
    }

    [Fact]
    public void RenewalPreservesTheOriginalDeadlineAndRejectsAnExpiredSession()
    {
        var account = Account.Register(Username.Parse("Player"), "password-hash", [1]);
        var start = DateTimeOffset.UnixEpoch;
        var session = account.OpenSession([2], start);
        var renewed = session.Renew([3], start.AddDays(29));

        Assert.NotNull(renewed);
        Assert.Equal(start.AddDays(30), renewed.ExpiresAt);
        Assert.Equal(account.Id, renewed.AccountId);
        Assert.Null(renewed.Renew([4], renewed.ExpiresAt));
    }
}
