using System.Security.Cryptography;
using Microsoft.AspNetCore.Identity;
using Microsoft.Extensions.Options;
using PongGame.Auth.Application;
using PongGame.Auth.Domain;

namespace PongGame.Auth.Infrastructure.Security;

internal sealed class PasswordHashing : IPasswordHasher
{
    private readonly PasswordHasher<string> hasher = new(Options.Create(
        new PasswordHasherOptions { IterationCount = 210_000 }));
    private readonly string missingAccountHash;

    public PasswordHashing()
    {
        missingAccountHash = hasher.HashPassword("", Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)));
    }

    public string Hash(Password password) => hasher.HashPassword("", password.Value);

    public bool Verify(Password password, string? storedHash) =>
        hasher.VerifyHashedPassword("", storedHash ?? missingAccountHash, password.Value) != PasswordVerificationResult.Failed;
}
