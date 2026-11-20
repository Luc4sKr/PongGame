using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using PongGame.Auth.Application;
using PongGame.Auth.Infrastructure.Persistence;
using PongGame.Auth.Infrastructure.Security;

namespace PongGame.Auth.Infrastructure;

public static class DependencyInjection
{
    public static IServiceCollection AddAuthInfrastructure(this IServiceCollection services, string connectionString)
    {
        services.AddDbContext<AuthDbContext>(options => options.UseNpgsql(connectionString));
        services.AddScoped<IAccountRepository, AccountRepository>();
        services.AddSingleton<IPasswordHasher, PasswordHashing>();
        services.AddSingleton<TokenIssuer>();
        services.AddSingleton<ITokenIssuer>(sp => sp.GetRequiredService<TokenIssuer>());
        return services;
    }

    public static async Task MigrateAuthDatabaseAsync(this IServiceProvider services)
    {
        await using var scope = services.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<AuthDbContext>();
        await db.Database.MigrateAsync();
    }
}
