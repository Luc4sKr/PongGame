using Microsoft.EntityFrameworkCore;
using PongGame.Auth.Domain;

namespace PongGame.Auth.Infrastructure.Persistence;

public sealed class AuthDbContext(DbContextOptions<AuthDbContext> options) : DbContext(options)
{
    internal DbSet<Account> Accounts => Set<Account>();
    internal DbSet<RefreshSession> Sessions => Set<RefreshSession>();

    protected override void OnModelCreating(ModelBuilder model)
    {
        var account = model.Entity<Account>();
        account.ToTable("auth_users");
        account.HasKey(a => a.Id).HasName("auth_users_pkey");
        account.Property(a => a.Id).HasColumnName("id").ValueGeneratedNever();
        account.ComplexProperty(a => a.Username, name =>
        {
            name.IsRequired();
            name.Property(n => n.Value).HasColumnName("username").HasMaxLength(32).UseCollation("C");
        });
        // The migration defines the unique PostgreSQL expression index on lower(username).
        account.Property(a => a.PasswordHash).HasColumnName("password_hash");
        account.Property(a => a.RecoveryHash).HasColumnName("recovery_hash").IsConcurrencyToken();

        var session = model.Entity<RefreshSession>();
        session.ToTable("auth_sessions");
        session.HasKey(s => s.TokenHash).HasName("auth_sessions_pkey");
        session.Property(s => s.TokenHash).HasColumnName("token_hash").ValueGeneratedNever();
        session.Property(s => s.AccountId).HasColumnName("user_id");
        session.Property(s => s.ExpiresAt).HasColumnName("expires_at");
        session.HasIndex(s => s.AccountId).HasDatabaseName("auth_sessions_user_idx");
        session.HasOne(s => s.Account).WithMany().HasForeignKey(s => s.AccountId)
            .HasConstraintName("auth_sessions_user_id_fkey").OnDelete(DeleteBehavior.Cascade);
    }
}
