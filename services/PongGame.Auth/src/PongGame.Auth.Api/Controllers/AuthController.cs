using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using PongGame.Auth.Application;

namespace PongGame.Auth.Api.Controllers;

[ApiController]
[Route("api/auth")]
[EnableRateLimiting("credentials")]
[ProducesResponseType<ValidationProblemDetails>(StatusCodes.Status400BadRequest)]
[ProducesResponseType(StatusCodes.Status429TooManyRequests)]
[RequestSizeLimit(8_192)]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class AuthController(AccountService accounts) : ControllerBase
{
    [HttpPost("register")]
    [ProducesResponseType<RegisteredAccount>(StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    public async Task<ActionResult<RegisteredAccount>> Register(Credentials request, CancellationToken ct)
    {
        var account = await accounts.RegisterAsync(request.Username, request.Password, ct);
        return account is null ? Conflict() : StatusCode(StatusCodes.Status201Created, account);
    }

    [HttpPost("login")]
    [ProducesResponseType<TokenPair>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status401Unauthorized)]
    public async Task<ActionResult<TokenPair>> Login(Credentials request, CancellationToken ct)
    {
        var tokens = await accounts.LoginAsync(request.Username, request.Password, ct);
        return tokens is null ? Unauthorized() : Ok(tokens);
    }

    [HttpPost("refresh")]
    [ProducesResponseType<TokenPair>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status401Unauthorized)]
    public async Task<ActionResult<TokenPair>> Refresh(RefreshRequest request, CancellationToken ct)
    {
        var tokens = await accounts.RefreshAsync(request.RefreshToken, ct);
        return tokens is null ? Unauthorized() : Ok(tokens);
    }

    [HttpPost("recover")]
    [ProducesResponseType<RecoveryResult>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status401Unauthorized)]
    public async Task<ActionResult<RecoveryResult>> Recover(RecoveryRequest request, CancellationToken ct)
    {
        var result = await accounts.RecoverAsync(request.Username, request.RecoveryCode, request.NewPassword, ct);
        return result is null ? Unauthorized() : Ok(result);
    }
}
