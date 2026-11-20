using Microsoft.EntityFrameworkCore.Migrations;

namespace PongGame.Auth.Infrastructure.Persistence.Migrations;

public partial class AdoptAuthSchema : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        // Both the original service and EF can initialize an empty database during a rollout.
        migrationBuilder.Sql("""
            SELECT pg_advisory_xact_lock(1886350951, 1);
            CREATE TABLE IF NOT EXISTS auth_users (
                id uuid PRIMARY KEY,
                username varchar(32) COLLATE "C" NOT NULL,
                password_hash text NOT NULL,
                recovery_hash bytea NOT NULL
            );
            CREATE UNIQUE INDEX IF NOT EXISTS auth_users_username_idx ON auth_users (lower(username));

            CREATE TABLE IF NOT EXISTS auth_sessions (
                token_hash bytea PRIMARY KEY,
                user_id uuid NOT NULL REFERENCES auth_users(id) ON DELETE CASCADE,
                expires_at timestamptz NOT NULL
            );
            CREATE INDEX IF NOT EXISTS auth_sessions_user_idx ON auth_sessions (user_id);
            """);
    }

    protected override void Down(MigrationBuilder migrationBuilder)
    {
        // Rolling back adoption only removes the EF history entry. Legacy accounts and sessions stay usable.
    }
}
