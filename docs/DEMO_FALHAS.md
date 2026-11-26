# Diagnóstico de falhas do PongGame

Use o [dashboard PongGame](http://127.0.0.1:3000/d/ponggame) para acompanhar o ambiente iniciado com `docker compose up --build -d --wait`. O painel **Traces de partidas** lista o pareamento e permite abrir a operação no Tempo. Em uma falha, filtre pelo identificador da partida em `pong.match.id` e acompanhe os serviços indicados abaixo.

| Cenário | Serviços | Evidência esperada |
| --- | --- | --- |
| F1 · Falha no provisionamento | Game, Matchmaking | `arena.provision` com erro, `saga.compensate`, tickets `COMPENSATED` e vaga liberada. |
| F2 · Consumidor interrompido | RabbitMQ, Game | Mesmo `messaging.message.id` reentregue, `messaging.rabbitmq.message.redelivered=true` na réplica sobrevivente e tickets `READY`. |
| F3 · Duplicação | RabbitMQ, Player | `pong.match.recorded=false` e `pong.message.duplicate=true` nas cópias descartadas. Um único registro em `processed_messages`. |
| F4 · Mensagem inválida | RabbitMQ, Player | Rejeição do payload nos logs, mensagem em `player.match.finished.dlq` e ausência de atualização do histórico para a mensagem inválida. |
| F5 · Disputa por vagas | Matchmaking, Redis | Respostas HTTP `201` e `409`, com ocupação limitada à capacidade configurada. |
| F6 · Broker indisponível | RabbitMQ, Matchmaking, Game | Crescimento das outboxes e publicação dos eventos pendentes após a reconexão, no trace original. |
| F7 · Resposta tardia | Matchmaking, Game | `saga.provisioning.timeout`, compensação e cancelamento da arena que respondeu após o prazo. |
| F8 · Distribuição entre réplicas | RabbitMQ, Game | Consumo por diferentes valores de `service.instance.id` e um provisionamento por partida. |

## Consultar o ambiente

```sh
docker compose ps -a
docker compose logs --follow --timestamps matchmaking player game-1 game-2 rabbitmq
docker compose exec -T postgres-player psql -U pong_player -c "SELECT message_id, processed_at FROM processed_messages ORDER BY processed_at DESC LIMIT 5"
```

A [interface do RabbitMQ](http://127.0.0.1:15672) mostra consumidores, mensagens aguardando ACK e filas de mensagens rejeitadas. Se uma réplica terminar antes de exportar seus spans, consulte também a reentrega registrada pela réplica sobrevivente e a métrica de reentregas do broker.
