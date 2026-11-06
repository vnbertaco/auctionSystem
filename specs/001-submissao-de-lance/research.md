# Research: Submissão de Lance

Stack e restrições já vêm da constituição v1.2.0; as decisões abaixo cobrem o que
a spec e a constituição deixam em aberto.

## R1. Entrega do resultado ao autor (FR-006a)
- **Decision**: WebSocket (STOMP) com destino por usuário `/user/queue/bid-results` e broadcast em `/topic/auctions/{auction_id}`.
- **Rationale**: stack já inclui `spring-websocket`; STOMP dá destinos por usuário e por tópico sem protocolo próprio.
- **Alternatives**: SSE (um canal por tipo, sem destino por usuário pronto); polling por `bid_id` (descartado pela clarificação B).
- **Limite**: broker STOMP em memória, logo uma instância de `bid-service` entrega o resultado; escalar exigiria broker externo (fora de escopo).

## R2. Garantir `LogAppendTime` (Princípio II)
- **Decision**: criar o tópico `bids` com `message.timestamp.type=LogAppendTime`; o consumer lê `ConsumerRecord.timestamp()`.
- **Rationale**: o broker sobrescreve o timestamp do produtor, então `end_time` não depende do cliente.
- **Alternatives**: usar `timestamp_cliente` (manipulável); relógio do consumer (não determinístico em reprocessamento).

## R3. Atomicidade e at-least-once
- **Decision**: uma transação por lance: checa `bid_id`, insere em `bids` e, se aceito, faz `UPDATE auctions ... WHERE version = ?`. Ack manual do offset só após commit. Colisão de versão: rollback, recarregar estado, reprocessar (limite de 3 tentativas, depois log de erro e retry do listener).
- **Rationale**: reprocesso após crash encontra a linha em `bids` e apenas reenvia o resultado (FR-004).
- **Alternatives**: transactional outbox / exactly-once do Kafka (complexidade além da demo).

## R4. Idempotência
- **Decision**: PK `bid_id` em `bids` + consulta prévia; `INSERT` trata violação de PK como duplicata. Resultado original reenviado pelo WebSocket.
- **Limite aceito**: duplicata de lance para leilão inexistente não tem linha, então é reavaliada e dá o mesmo `AUCTION_NOT_FOUND` (resultado igual, sem efeito colateral).

## R5. `sequence`
- **Decision**: `sequence` = offset Kafka (com `partition` gravada). Único e crescente por partição, logo por `auction_id`.
- **Alternatives**: contador por leilão (exigiria escrita extra e reintroduz contenção).

## R6. Encerramento
- **Decision**: lance rejeitado com `AUCTION_CLOSED` se `status = encerrado` ou `LogAppendTime >= end_time`. O consumer não muda `status`; o encerramento é do cadastro (externo, spec Suposições).
- **Rationale**: evita segundo escritor sobre `auctions`.

## R7. Append-only
- **Decision**: trigger `BEFORE UPDATE OR DELETE` em `bids` que lança exceção, além de a aplicação nunca emitir esses comandos.
- **Rationale**: torna o Princípio VII verificável por teste de integração.

## R8. Autenticação e rate limit simulados (Princípio VI)
- **Decision**: filtro de Gateway valida JWT HS256 com segredo local; `user_id` = claim `sub` (nunca vem do corpo). Rate limit por `user_id`/IP com Bucket4j em memória, padrão 20 req/s por usuário (configurável); excesso → `429`.
- **Rationale**: o `RequestRateLimiter` nativo exige Redis, que é fora do escopo.
- **Alternatives**: Redis (fora de escopo); serviço externo de auth (proibido).

## R9. Controle do `risk_tier`
- **Decision**: `BidController` consulta `risk_tier` (cache local curto, TTL 30 s) e chama `BidPipeline.submit(command, tier)`. Leilão desconhecido usa `normal` e segue ao consumer, que emite `AUCTION_NOT_FOUND`.

## R10. Cobertura de testes (FR-012)
- **Decision**: JaCoCo `check` com cobertura de linhas mínima 0.80 sobre o bundle (build falha abaixo disso), com exclusões só para classes de configuração e `main`. Regras de negócio isoladas em `BidProcessor`.
- **Nota**: "superior a 80%" implica a meta; o gate usa `0.80` e a meta de equipe é ≥ 81%.

## R11. Observabilidade
- **Decision**: `logstash-logback-encoder` (JSON), MDC com `bid_id`/`auction_id`; métricas `bids.received`, `bids.decided{decision,reason}`, `bids.latency` (histograma), `bids.version_collisions`; `bid_id` propagado como header Kafka.

## R12. Carga
- **Decision**: k6 com N usuários enviando valores crescentes ao mesmo `auction_id`, verificando ao final: `highest_bid` = maior valor válido, zero `bid_id` duplicado em `bids`, `sequence` crescente.
