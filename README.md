# PongGame
PonGame in a distributed architecture

## Autenticação com Docker Compose

Na raiz do repositório, crie um arquivo `.env` com `POSTGRES_PASSWORD=SuaSenhaLocal` e substitua o valor por uma senha própria antes da primeira execução. O Git ignora esse arquivo. Com o Docker em modo de contêineres Linux, execute:

```sh
docker compose up --build --wait
```

O Nginx expõe a API em `http://127.0.0.1:8080/api/auth`. Para verificar a inicialização, abra `http://127.0.0.1:8080/api/auth/jwks` e confira a chave pública retornada. As rotas de cadastro, login, renovação e recuperação estão no [contrato do Auth](services/PongGame.Auth/README.md#contrato-http).

O gateway usa HTTP local. O serviço de autenticação roda em modo de desenvolvimento e gera uma chave JWT temporária a cada inicialização. O PostgreSQL mantém as contas e sessões no volume `auth_data`. Para encerrar os contêineres e preservar os dados, execute `docker compose down`.
