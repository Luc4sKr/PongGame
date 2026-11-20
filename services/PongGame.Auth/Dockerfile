FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY src/PongGame.Auth.Api/*.csproj PongGame.Auth.Api/
COPY src/PongGame.Auth.Application/*.csproj PongGame.Auth.Application/
COPY src/PongGame.Auth.Domain/*.csproj PongGame.Auth.Domain/
COPY src/PongGame.Auth.Infrastructure/*.csproj PongGame.Auth.Infrastructure/
RUN dotnet restore PongGame.Auth.Api/PongGame.Auth.Api.csproj
COPY src/ ./
RUN dotnet publish PongGame.Auth.Api/PongGame.Auth.Api.csproj -c Release --no-restore -o /app

FROM mcr.microsoft.com/dotnet/aspnet:10.0
WORKDIR /app
COPY --from=build /app/ ./
USER $APP_UID
EXPOSE 8080
ENTRYPOINT ["dotnet", "PongGame.Auth.Api.dll"]
