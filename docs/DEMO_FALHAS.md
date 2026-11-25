# Demonstração de falhas do PongGame

## Antes da aula

Abra o Docker Desktop no modo de contêineres Linux. Na raiz do repositório, com Python 3.10+ e Docker Compose 2.24.4+ disponíveis, prepare as imagens. A primeira compilação precisa de internet. Repita a preparação se alterar um serviço.

```powershell
python scripts/demo_falhas.py preparar
```

Para ensaiar todos os cenários em sequência, execute `python scripts/demo_falhas.py todos`. Cada cenário cria um projeto Compose com volumes, portas locais e identificação próprios. As contas e as partidas pertencem àquela execução. O script usa a biblioteca padrão do Python.

As APIs, o RabbitMQ e o PostgreSQL são os do projeto. Para que as consultas de evidência leiam exatamente a mesma instância dos serviços, o Compose de demonstração aponta Game, Player e Matchmaking para o Redis daquele ensaio. O Auth usa o KMS emulado pelo MiniStack.

## Durante a apresentação

Substitua `F2` pelo cenário escolhido pelo professor. O comando abaixo aguarda os serviços ficarem prontos, pausa para você explicar o cenário e provoca a falha quando você aperta **Enter**. A segunda pausa permite conferir o resultado na stack em execução.

```powershell
python scripts/demo_falhas.py F2 --aguardar --inspecionar
```

Para rodar diretamente, use `python scripts/demo_falhas.py F2`. Reserve tempo para a inicialização dos contêineres. Em F2, a segunda réplica pode esperar até 30 segundos pela expiração da reserva de processamento no Redis. Em F6, a interrupção do broker dura pelo menos 30 segundos.

| Cenário | Evidência para mostrar |
|---|---|
| F1 · Compensação | A dependência falha em 100% das chamadas. Os dois tickets retornam `COMPENSATED` e o motivo da falha. No Redis, `slotReleased=1`, os dois bloqueios sumiram e a ocupação voltou a zero. |
| F2 · Consumidor interrompido | A fila registra uma entrega sem ACK. O script encerra a réplica que detém a reserva. O mesmo `messageId` reaparece na outra réplica com `redelivered=true`. Os dois tickets ficam `READY` e há um único log de provisionamento. |
| F3 · Duplicação | Cinco publicações idênticas produzem quatro descartes por duplicidade. A consulta ao PostgreSQL retorna `1\|1\|1\|1\|1` para o marcador da mensagem, os dois históricos e o total de partidas dos dois jogadores. |
| F4 · Mensagem venenosa | O log do Player registra três tentativas com o ID inválido. O script confere o conteúdo original na DLQ e consulta o efeito do evento válido enviado logo depois. A mensagem inválida continua disponível no painel RabbitMQ. |
| F5 · Disputa | Cem clientes partem juntos. O terminal exibe 10 respostas HTTP 201, 90 respostas HTTP 409 e os IDs das dez reservas confirmadas no Redis. Depois, o script libera as dez pela API. |
| F6 · Broker indisponível | Três partidas são criadas durante a interrupção. Os IDs aparecem na outbox do Redis. Após a volta do RabbitMQ, as três chegam a `READY`, as outboxes esvaziam e as filas voltam a ter consumidores. |
| F7 · Resposta tardia | Prazo de 3 s, latência de 12 s. A linha do tempo mostra o timeout, a compensação dos tickets, a chegada tardia de `match.ready` e o cancelamento da arena no Game. Uma segunda reserva continua ocupada durante todo o teste. Assim, uma liberação duplicada seria visível no contador. |
| F8 · Escala horizontal | O broker registra três consumidores. O terminal associa cada uma das seis partidas à réplica que a provisionou e confere os seis IDs ativos no Redis. As três réplicas precisam participar. |

## Conferir as evidências

O terminal imprime o nome do projeto e a pasta `artifacts/demo/<projeto>/`. Nela, `evidencias.log` contém a sequência observada e o resultado. `consultas.log` guarda os comandos Redis e SQL executados e suas respostas. Os arquivos dos serviços contêm os logs com horários em UTC. Use o mesmo `matchId` ou `messageId` para acompanhar uma operação de ponta a ponta.

Durante a pausa de inspeção, abra um segundo PowerShell na raiz e copie o nome impresso para `$projetoDemo`. Em F5, a pausa acontece enquanto as dez vagas ainda estão ocupadas para você consultá-las antes da liberação.

```powershell
$projetoDemo = '<nome impresso pelo script>'
$dc = @('compose', '-p', $projetoDemo, '-f', 'compose.yaml', '-f', 'compose.demo.yaml')
docker @dc ps -a
docker @dc logs --timestamps matchmaking player game-1 game-2
docker @dc exec -T redis redis-cli GET mm:arena:active_count
docker @dc exec -T postgres-player psql -U pong_player -c "SELECT message_id, processed_at FROM processed_messages ORDER BY processed_at DESC LIMIT 5"
```

Para F8, inclua `game-3` no comando de logs e use `--profile escala` nos argumentos do Compose. O painel RabbitMQ fica no endereço local impresso no terminal. A conta exclusiva do ambiente é `pong_demo`, com senha `pong_demo_local`.

Ao finalizar, pressione **Enter** no terminal da demonstração. O script salva os logs, remove os contêineres e os volumes daquela execução e retorna código zero se as verificações passaram. Uma divergência exibe o valor observado e encerra com erro. Se a janela for fechada à força, use o nome exato do projeto impresso na execução e rode `docker @dc --profile escala down --volumes` para liberar seus recursos.
