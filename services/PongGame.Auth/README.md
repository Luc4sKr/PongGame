# PongGame.Auth

API de autenticação do PongGame em .NET 10 e PostgreSQL. O nome de usuário aceita de 3 a 32 letras ASCII, dígitos, hífens e sublinhados. O cadastro preserva a grafia escolhida e o banco garante a unicidade mesmo com diferenças de maiúsculas e minúsculas. A senha aceita de 12 a 128 caracteres.

## Arquitetura

| Projeto | Responsabilidade |
| --- | --- |
| `PongGame.Auth.Domain` | Modela a conta, o nome de usuário, a senha e a sessão. A conta controla a troca das credenciais. A sessão controla seu prazo de renovação. |
| `PongGame.Auth.Application` | Orquestra cadastro, login, renovação e recuperação. Define os contratos de repositório, hashing de senha e emissão de tokens. |
| `PongGame.Auth.Infrastructure` | Implementa esses contratos, mapeia as entidades com EF Core e Npgsql e mantém as migrations do PostgreSQL. |
| `PongGame.Auth.Api` | Traduz requisições e resultados HTTP, configura o limite de chamadas e conecta as implementações por injeção de dependência. |

O domínio depende apenas do .NET e pode ser testado isoladamente. Os casos de uso dependem do domínio. Cada requisição recebe seu próprio `AuthDbContext`. A recuperação troca os hashes e revoga as sessões na mesma transação. Uma renovação concorrente só tem sucesso se o token apresentado ainda estiver vigente no banco.

## Executar localmente

Com o SDK .NET 10 instalado e um banco PostgreSQL criado, execute na pasta `services/PongGame.Auth` em PowerShell. Ajuste a conexão para o seu banco. A API aplica as migrations ao iniciar. O usuário do banco precisa de permissão para executar as alterações de esquema pendentes.

```powershell
$env:ConnectionStrings__AuthDb = 'Host=localhost;Database=pong_auth;Username=pong_auth;Password=SUA_SENHA_LOCAL'
dotnet run --project src/PongGame.Auth.Api --launch-profile http
```

A API atende em `http://localhost:5275`. O contrato OpenAPI de desenvolvimento fica em `/openapi/v1.json`.

A primeira migration adota as tabelas `auth_users` e `auth_sessions` da versão anterior, conserva seus dados e registra a versão em `__EFMigrationsHistory`. Em um banco vazio, ela cria as mesmas tabelas e o índice único sobre `lower(username)`.

Quando o modelo mudar, use a CLI `dotnet-ef` 10 com a conexão acima para gerar a próxima migration na infraestrutura.

```sh
dotnet ef migrations add NomeDaAlteracao --project src/PongGame.Auth.Infrastructure --startup-project src/PongGame.Auth.Api --output-dir Persistence/Migrations
```

## Contrato HTTP

As rotas abaixo usam o prefixo `/api/auth`. Envie os campos no corpo JSON.

| Método e rota | Campos | Resposta de sucesso |
| --- | --- | --- |
| `POST /register` | `username`, `password` | `201` com `userId`, `username` e `recoveryCode` |
| `POST /login` | `username`, `password` | `200` com `accessToken`, `tokenType`, `expiresIn` (segundos) e `refreshToken` |
| `POST /refresh` | `refreshToken` | `200` com um novo par de tokens |
| `POST /recover` | `username`, `recoveryCode`, `newPassword` | `200` com o novo `recoveryCode` |
| `GET /jwks` | — | `200` com as chaves públicas para validar os JWTs |

O cadastro retorna `409` para nome ocupado. Campos inválidos retornam `400`. Credenciais, códigos ou tokens de renovação inválidos retornam `401`. As quatro rotas POST compartilham um limite de 30 chamadas por minuto por IP, por instância. O excesso retorna `429`.

O frontend deve mostrar o código de recuperação no cadastro para o jogador guardá-lo. A recuperação exige o nome e esse código, troca a senha e entrega um código substituto. Quem perder tanto a senha quanto o código perde o acesso à conta. O banco guarda hashes das senhas, dos códigos e dos tokens de renovação.

Cada login cria uma sessão com validade de 30 dias. Cada renovação consome o token anterior e mantém a data de vencimento da sessão. A recuperação revoga todas as sessões de renovação do jogador. JWTs já emitidos continuam válidos até a própria expiração, em até 15 minutos.

## Integração com os serviços do jogo

Configure `Jwt__PrivateKeyPem` com o conteúdo de uma chave privada RSA em PEM (mínimo de 2048 bits). Distribua a mesma chave às instâncias do Auth por meio do gerenciador de segredos. Em desenvolvimento, a API gera uma chave temporária quando essa variável está ausente. Reiniciar a instância nessa condição invalida a assinatura dos tokens anteriores para a nova chave pública.

Os serviços consumidores devem carregar a chave de `GET /api/auth/jwks` por um endereço confiável, selecionar a chave pelo `kid` e validar a assinatura RS256, o emissor, a audiência e o intervalo de validade. Os padrões são `PongGame.Auth` para `Jwt__Issuer` e `PongGame` para `Jwt__Audience`. O claim `sub` contém o UUID estável do jogador. O claim `preferred_username` contém o nome com a grafia do cadastro.

No gateway, exponha a API por HTTPS e encaminhe `X-Forwarded-For` e `X-Forwarded-Proto`. Quando o Nginx estiver em outro host ou contêiner, configure `Gateway__Address` na API com o IP do proxy que se conecta a ela.

## Testes

Para rodar os testes das regras de domínio, execute `dotnet test PongGame.Auth.slnx` na pasta do serviço.

Com a API em execução sobre um banco de teste, rode o comando abaixo na pasta do serviço usando Node.js 22 ou superior. O teste cria uma conta descartável e cobre a disputa por um nome, a assinatura do JWT, a rotação concorrente de tokens e a recuperação com revogação de múltiplas sessões. Para outra porta, configure `AUTH_TEST_URL` com a URL base da API.

```sh
node --test tests/auth.test.mjs
```

Para testar duas réplicas, defina também `AUTH_TEST_PEER_URL` com o endereço da segunda instância. Ambas devem usar o mesmo banco, emissor, audiência e chave de assinatura.
