using System.Net;
using OpenTelemetry.Logs;
using OpenTelemetry.Metrics;
using OpenTelemetry.Trace;
using System.Threading.RateLimiting;
using Microsoft.AspNetCore.HttpOverrides;
using PongGame.Auth.Application;
using PongGame.Auth.Infrastructure;
using PongGame.Auth.Infrastructure.Security;

var builder = WebApplication.CreateBuilder(args);
if (!string.IsNullOrWhiteSpace(builder.Configuration["OTEL_EXPORTER_OTLP_ENDPOINT"]))
{
    builder.Services.AddOpenTelemetry()
        .WithTracing(tracing => tracing
            .AddAspNetCoreInstrumentation(options => options.Filter = context => context.Request.Path != "/health")
            .AddHttpClientInstrumentation()
            .AddSource("Npgsql")
            .AddOtlpExporter())
        .WithMetrics(metrics => metrics
            .AddAspNetCoreInstrumentation()
            .AddHttpClientInstrumentation()
            .AddRuntimeInstrumentation()
            .AddOtlpExporter());
    builder.Logging.AddOpenTelemetry(logging =>
    {
        logging.IncludeFormattedMessage = true;
        logging.IncludeScopes = true;
        logging.AddOtlpExporter();
    });
}
builder.Services.AddControllers();
builder.Services.AddOpenApi();
builder.Services.AddProblemDetails();
builder.Services.AddAuthInfrastructure(builder.Configuration.GetConnectionString("AuthDb")
    ?? throw new InvalidOperationException("Configure ConnectionStrings:AuthDb."));
builder.Services.AddSingleton(TimeProvider.System);
builder.Services.AddScoped<AccountService>();

builder.Services.Configure<ForwardedHeadersOptions>(options =>
{
    options.ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto;
    if (builder.Configuration["Gateway:Address"] is { } address)
        options.KnownProxies.Add(IPAddress.Parse(address));
});
builder.Services.AddRateLimiter(options =>
{
    options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    options.AddPolicy("credentials", context => RateLimitPartition.GetFixedWindowLimiter(
        context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
        _ => new FixedWindowRateLimiterOptions { PermitLimit = 30, Window = TimeSpan.FromMinutes(1) }));
});

var app = builder.Build();
var tokens = app.Services.GetRequiredService<TokenIssuer>();
await tokens.InitializeAsync();
await app.Services.MigrateAuthDatabaseAsync();

app.UseForwardedHeaders();
app.UseExceptionHandler();
app.UseRouting();
app.Use(async (context, next) =>
{
    if (System.Diagnostics.Activity.Current is { } activity)
        context.Response.Headers["X-Trace-Id"] = activity.TraceId.ToString();
    await next();
});
app.UseRateLimiter();
app.MapControllers();
app.MapGet("/api/auth/jwks", () => tokens.PublicKeys);
app.MapGet("/health", async (IServiceProvider sp, CancellationToken ct) =>
{
    var healthy = await sp.CanConnectAuthDatabaseAsync(ct);
    return healthy
        ? Results.Ok(new { status = "UP", service = "auth-service" })
        : Results.Json(new { status = "DOWN", service = "auth-service" }, statusCode: StatusCodes.Status503ServiceUnavailable);
});
if (app.Environment.IsDevelopment())
    app.MapOpenApi();

await app.RunAsync();
