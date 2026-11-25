# Sessão de autenticação no PongGame

Cada login abre uma sessão de renovação no PostgreSQL do **PongGame.Auth**. Só o Auth consulta essa sessão. Para acessar o perfil, entrar na fila ou conectar-se a uma partida, o frontend apresenta um JWT de acesso. **Player, Matchmaking e Game validam esse JWT localmente** e identificam o jogador pelo campo `sub`, que contém o ID da conta.

| Credencial entregue no login | Quem a recebe depois do login | Validade e finalidade |
| --- | --- | --- |
| `accessToken` | Player, Matchmaking e Game | JWT assinado com RS256, válido por 15 minutos. Leva o ID da conta em `sub` e o nome do jogador em `preferred_username`. |
| `refreshToken` | Auth, em `POST /api/auth/refresh` | Segredo aleatório de uso único. Permite obter outro par de tokens até 30 dias após o login que abriu aquela sessão. |

## Do login à renovação

1. **Cadastro.** `POST /api/auth/register` cria a conta e devolve o código de recuperação. A sessão começa quando o jogador faz login.
2. **Login.** Em `POST /api/auth/login`, o Auth verifica usuário e senha. Em seguida, grava uma linha em `auth_sessions` com `user_id`, `expires_at` e `token_hash` (SHA-256 do refresh token). Devolve os dois tokens ao frontend, que os guarda no `sessionStorage` da aba. Logins em dispositivos diferentes criam sessões independentes para a mesma conta.
3. **Uso.** Nas chamadas HTTP protegidas, o frontend envia `Authorization: Bearer <accessToken>`. O serviço de destino verifica a assinatura, o emissor, a audiência e a validade temporal do JWT. No Compose, o Auth assina via KMS e os três serviços obtêm a chave pública diretamente do KMS e a mantêm em cache. O Auth também publica `GET /api/auth/jwks`, mas os três serviços usam o KMS como fonte da chave.
4. **Renovação.** Quando uma chamada HTTP protegida recebe `401`, o frontend envia o refresh token ao Auth. Se a sessão estiver vigente, o Auth substitui o hash no banco e devolve um novo par. O frontend salva o par e repete a chamada original uma vez. Chamadas simultâneas na mesma aba compartilham a tentativa de renovação.
5. **Vencimento.** A rotação preserva o `expires_at` original. Um token de renovação já consumido ou vencido recebe `401`. Se a tentativa de renovação falhar, o frontend descarta os tokens locais e volta à tela de autenticação.

Por exemplo, se o jogador renovar no 29º dia, receberá um JWT válido por 15 minutos. O novo refresh token continuará vencendo no 30º dia contado a partir do login.

## Efeito em cada componente

| Componente | Como a sessão afeta seu funcionamento |
| --- | --- |
| **Gateway (Nginx)** | Encaminha as requisições HTTP e a abertura do WebSocket ao serviço de destino. A validação do JWT fica no serviço que atende a rota. |
| **Player** | Em `GET /api/players/me`, usa o ID e o nome do JWT para buscar ou criar o perfil. Em `GET /api/players/me/matches`, usa o ID para consultar o histórico. O ranking em `GET /api/players/leaderboard` é público. |
| **Matchmaking** | Ao entrar na fila, associa o ticket ao ID e ao nome do JWT. Ao sair, usa o ID para localizar o ticket do jogador. Consultar um ticket e operar reservas de arena também exige autenticação. `GET /api/matchmaking/status` é público. |
| **Game** | O frontend abre `/ws/game/{matchId}?token=<accessToken>` pelo gateway. Antes de aceitar a conexão, o Game valida o JWT e verifica se o ID corresponde a um dos dois jogadores. Um token inválido resulta em `401`. Um jogador autenticado fora da partida recebe `403`. A consulta HTTP em `/api/game/matches/{matchId}` também exige autenticação. |
| **RabbitMQ e Redis** | O fluxo de partidas usa IDs de jogadores e de partidas. Por exemplo, o evento `match.finished` permite ao Player atualizar o histórico e ao Matchmaking liberar a arena, mesmo que o jogador já tenha saído da aplicação. Esse processamento independe dos tokens do navegador. |

## Saída, recuperação e partida em andamento

- **Sair da aplicação** limpa os tokens da aba e retorna à tela de autenticação. O frontend não chama um endpoint de logout. A linha em `auth_sessions` continua no banco, e o refresh token que estava vigente continua utilizável até ser consumido, vencer ou ser revogado pela recuperação.
- **Recuperar a conta** com `POST /api/auth/recover` troca a senha e o código de recuperação e exclui todas as sessões de renovação da conta na mesma transação. Isso afeta todos os dispositivos. Os JWTs já emitidos ainda podem ser aceitos até a própria expiração, pois os serviços validam a assinatura e o prazo sem consultar `auth_sessions`.
- **Durante uma partida**, o Game autentica o jogador na abertura do WebSocket. A expiração do JWT ou a recuperação da conta, por si só, não encerra uma conexão já aceita. Uma nova conexão passa novamente pela validação. A renovação automática após `401` pertence ao cliente HTTP do frontend e não é executada na abertura do WebSocket.
- **Se o Auth ficar indisponível**, login e renovação ficam indisponíveis. Requisições com JWT ainda válido podem continuar sendo atendidas enquanto o serviço de destino dispuser da chave pública para verificá-lo.

## Referências no código

- [Casos de uso do Auth](../services/PongGame.Auth/src/PongGame.Auth.Application/AccountService.cs), [prazo de renovação](../services/PongGame.Auth/src/PongGame.Auth.Domain/RefreshSession.cs) e [persistência e revogação das sessões](../services/PongGame.Auth/src/PongGame.Auth.Infrastructure/Persistence/AccountRepository.cs).
- [Emissão do JWT](../services/PongGame.Auth/src/PongGame.Auth.Infrastructure/Security/TokenIssuer.cs) e [configuração de chave, emissor e audiência no Compose](../compose.yaml).
- [Armazenamento e renovação no frontend](../frontend/src/api.ts), [saída da aplicação](../frontend/src/App.tsx) e [abertura do WebSocket](../frontend/src/components/GameArenaView.tsx).
- [Rotas do Player](../services/player-service/src/main/java/com/ponggame/player/web/PlayerController.java), [rotas do Matchmaking](../services/matchmaking-service/src/main/java/com/ponggame/matchmaking/web/MatchmakingController.java), [autenticação e participação na partida](../services/game-service/internal/ws/handler.go) e [contratos de eventos](../contracts/asyncapi.yaml).
