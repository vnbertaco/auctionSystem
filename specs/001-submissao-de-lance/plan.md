# Implementation Plan: Submissão de Lance em Leilão Concorrente

**Branch**: `001-submissao-de-lance` | **Date**: 2026-11-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-submissao-de-lance/spec.md`

## Summary

Receber lances por HTTP (com filtros de entrada que simulam autenticação e
rate limit), publicá-los no Kafka com `key = auction_id` (único consumer ativo
por partição = single writer) e processá-los em ordem de offset. O consumer
verifica idempotência por `bid_id`, aplica as regras de negócio (valor,
`starting_price`, vendedor/líder, `end_time` pelo `LogAppendTime`), grava o
histórico append-only em `bids` e o estado em `auctions` com lock otimista
(`version`) na mesma transação, e só então confirma o offset. O resultado vai ao
autor pela conexão WebSocket (resposta HTTP é só `202 Recebido`) e o novo líder
é transmitido por broadcast. A decisão passa pela abstração `BidPipeline` (com
`risk_tier`) e tudo é observável (log JSON + métricas).

## Technical Context

**Language/Version**: Java 21 (Virtual Threads onde aplicável)

**Primary Dependencies**: Spring Boot 4.x (`spring-kafka`, `spring-data-jpa`,
`spring-websocket`, `spring-boot-starter-actuator`),
Flyway, Micrometer, Logback JSON encoder, Bucket4j (rate limit local em memória)

**Storage**: PostgreSQL (tabelas `auctions` e `bids`)

**Testing**: JUnit 5, Mockito, AssertJ (unitários); Testcontainers (Kafka +
PostgreSQL) para integração; k6 para carga; JaCoCo com gate de cobertura de
linhas (FR-012)

**Target Platform**: Docker / Docker Compose local (Linux)

**Project Type**: Web service (aplicação Spring Boot única)

**Performance Goals**: sem SLA formal (spec); meta de demonstração: centenas de
lances concorrentes no mesmo `auction_id` sem perda, duplicação ou reordenação

**Constraints**: single writer por `auction_id` (NON-NEGOTIABLE); at-least-once
com idempotência; sem serviços cloud; cobertura de testes unitários > 80%

**Scale/Scope**: 1 tópico Kafka (6 partições), 1 instância de `bid-service`
no compose (consumer group preparado para mais), demo local

Nenhum `NEEDS CLARIFICATION` pendente (ver [research.md](research.md)).

## Constitution Check

*GATE: avaliado antes da Fase 0 e reavaliado após a Fase 1 (constitution v1.3.0).*

| Princípio | Como o plano atende | Status |
|---|---|---|
| I. Single writer | Tópico `bids` com `key = auction_id`; um consumer ativo por partição; sem processamento paralelo do mesmo item (listener com concorrência = nº de partições, cada partição sequencial) | Pass |
| II. Ordem e idempotência | `bid_id` UUID global (PK de `bids`); checagem antes de mudar estado; ordem = offset; `end_time` comparado ao `LogAppendTime` (tópico com `message.timestamp.type=LogAppendTime`) | Pass |
| III. Lock otimista | `UPDATE auctions ... WHERE auction_id=? AND version=?` com `version+1`; 0 linhas afetadas = colisão, logada como anomalia e reprocessada | Pass |
| IV. `risk_tier` | `BidPipeline.submit(bid, riskTier)` com única implementação `KafkaBidPipeline`; tier vai no header da mensagem e nos logs | Pass |
| V. Observabilidade | Log JSON com `auction_id, bid_id, user_id, decision, sequence, latency_ms`; métricas Micrometer; `bid_id` como correlation ID (MDC e header Kafka) | Pass |
| VI. Escopo local | Docker Compose; auth (JWT HS256 simulado) e rate limit (Bucket4j em memória) como filtros servlet da própria aplicação (`JwtAuthFilter`, `RateLimitFilter`), documentados como simulação | Pass |
| VII. Append-only | `bids` sem UPDATE/DELETE (trigger no banco bloqueia); lance de leilão existente gera linha; `AUCTION_NOT_FOUND` só em log; sem event sourcing | Pass |

Sem violações; a tabela de Complexity Tracking não se aplica.

**Re-check pós-Fase 1**: o desenho de dados e contratos manteve todos os
princípios (FK `bids → auctions` explica a exceção `AUCTION_NOT_FOUND`). Pass.

## Project Structure

### Documentation (this feature)

```text
specs/001-submissao-de-lance/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── openapi.yaml
│   ├── websocket.md
│   └── kafka-message.md
└── tasks.md             # gerado por /speckit-tasks
```

### Source Code (repository root)

```text
pom.xml
Dockerfile
docker-compose.yml                # app, kafka, kafka-init, postgres
src/main/java/com/auction/bid/
├── api/                          # BidController (POST /bids), DTOs, validação
├── pipeline/                     # BidPipeline, KafkaBidPipeline, RiskTier
├── consumer/                     # BidListener (ack manual pós-commit)
├── domain/                       # BidProcessor (regras puras), BidTransactionService, Decision, Reason
├── persistence/                  # AuctionRepository (UPDATE com version), BidRepository
├── realtime/                     # ResultNotifier, LeaderBroadcaster
├── gateway/                      # JwtAuthFilter, RateLimitFilter (simulações)
├── config/                       # Kafka, WebSocket, SecurityFilterConfig
└── observability/                # BidLogger, métricas
src/main/resources/db/migration/  # Flyway V1__schema.sql, V2__bids_append_only.sql
src/test/java/com/auction/bid/    # unit e integration
load-tests/                       # k6, seed.sql, gen-jwt.sh
```

**Structure Decision**: aplicação Maven única; `BidProcessor` concentra as
regras de negócio sem dependência de framework para permitir cobertura unitária
> 80% (FR-012).

## Complexity Tracking

Não aplicável (nenhuma violação da constituição).
