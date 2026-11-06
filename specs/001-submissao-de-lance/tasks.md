# Tasks: Submissão de Lance em Leilão Concorrente

**Input**: `/specs/001-submissao-de-lance/` (plan.md, spec.md, data-model.md, research.md, contracts/, quickstart.md)
**Prerequisites**: plan.md, spec.md
**Tests**: INCLUÍDOS — FR-012 exige cobertura unitária > 80% (JaCoCo gate) e a constituição exige teste de carga k6. Testes de integração via Testcontainers (Kafka + PostgreSQL). **Todos os testes automatizados são escritos na Phase 5, depois de toda a implementação.**
**Organization**: tarefas agrupadas por user story (cenários de aceitação da spec).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: pode rodar em paralelo (arquivos diferentes, sem dependência incompleta)
- **[Story]**: US1..US2
- Aplicação Spring Boot única (um `pom.xml`, sem módulos), pacote base `com.auction.bid`. Caminhos abreviados: `BID=src/main/java/com/auction/bid`, `BIDT=src/test/java/com/auction/bid`, `GW=src/main/java/com/auction/bid/gateway` (filtros de auth/rate limit), `GWT=src/test/java/com/auction/bid/gateway`.

## User Stories (derivadas da spec)

- **US1 (P1) 🎯 MVP**: Submeter lance e receber resultado aceito/rejeitado (cenários 1, 2, 3, 4 + regras de vendedor/líder/encerrado/inexistente), com broadcast do novo líder e idempotência por `bid_id` (reenvio devolve o resultado original sem reprocessar).
- **US2 (P2)**: Borda de entrada — autenticação JWT simulada (cenário 5) e rate limit (cenário 6), como filtros servlet na própria aplicação.

---

## Phase 1: Setup

- [X] T001 Criar `pom.xml` único na raiz (Java 21, parent `spring-boot-starter-parent` 4.x, packaging jar) com dependências: spring-boot-starter-web, spring-kafka, spring-boot-starter-data-jpa, spring-boot-starter-websocket, spring-boot-starter-actuator, spring-boot-starter-validation, micrometer-registry-prometheus, flyway-core + flyway-database-postgresql, postgresql, logstash-logback-encoder, bucket4j-core, jjwt (HS256); test: spring-boot-starter-test, spring-kafka-test, testcontainers (kafka, postgresql, junit-jupiter), awaitility
- [X] T002 Configurar no `pom.xml` o plugin JaCoCo (`prepare-agent`, `report`, `check` de cobertura de linhas mínima 0.80, excluindo apenas `*Application` e `config/`), `spring-boot-maven-plugin` e Maven Wrapper (`mvnw`)
- [X] T003 [P] Criar `BID/BidApplication.java`, `src/main/resources/application.yml` (placeholders de env para Kafka, Postgres, `JWT_SECRET` e `ratelimit.requests-per-second`, padrão 20), `.gitignore`, `.dockerignore` e `Dockerfile` multi-stage na raiz (JDK 21, executa o jar)
- [X] T004 [P] Criar `docker-compose.yml` na raiz com serviços `postgres`, `kafka` (modo KRaft), `kafka-init` (cria tópico `bids` com 6 partições e `message.timestamp.type=LogAppendTime`) e `app` (build via `Dockerfile` da raiz)

---

## Phase 2: Foundational (bloqueia todas as stories)

- [X] T005 Criar migração Flyway `src/main/resources/db/migration/V1__schema.sql` com tabelas `auctions` (auction_id uuid PK; item_name varchar(200) NOT NULL; seller_id varchar(64) NOT NULL; risk_tier varchar(10) NOT NULL CHECK IN ('normal','hot'); starting_price numeric(14,2) CHECK > 0; end_time timestamptz NOT NULL; highest_bid numeric(14,2) NULL; highest_user varchar(64) NULL; version bigint NOT NULL DEFAULT 0; status varchar(10) CHECK IN ('ativo','encerrado')) e `bids` (bid_id uuid PK; auction_id uuid NOT NULL FK→auctions; user_id varchar(64) NOT NULL; valor numeric(14,2) CHECK > 0; timestamp_cliente timestamptz; log_append_time timestamptz; partition int; sequence bigint; decision varchar(10) CHECK IN ('aceito','rejeitado'); reason varchar(40) NULL; processed_at timestamptz) + índice `(auction_id, sequence)`
- [X] T006 Criar `src/main/resources/db/migration/V2__bids_append_only.sql` com trigger `BEFORE UPDATE OR DELETE` em `bids` que lança exceção (Princípio VII)
- [X] T007 [P] Criar enums/records de domínio em `BID/domain/`: `RiskTier.java` (`normal`,`hot`), `Decision.java` (`aceito`,`rejeitado`), `Reason.java` (`BELOW_CURRENT_BID`, `BELOW_STARTING_PRICE`, `SELLER_CANNOT_BID`, `ALREADY_LEADING`, `AUCTION_CLOSED`, `AUCTION_NOT_FOUND`), `BidCommand.java` (bid_id, auction_id, user_id, valor, timestamp_cliente), `BidResult.java` (bid_id, auction_id, decision, reason, sequence, duplicate)
- [X] T008 [P] Criar entidades JPA `BID/persistence/AuctionEntity.java` e `BID/persistence/BidEntity.java` mapeando exatamente as colunas de T005 (`BidEntity` sem setters públicos após construção; sem @Version no JPA — o lock é via UPDATE explícito)
- [X] T009 Criar `BID/persistence/AuctionRepository.java` (consulta por id + método `int updateLeader(auctionId, highestBid, highestUser, expectedVersion)` via `UPDATE auctions SET highest_bid=?, highest_user=?, version=version+1 WHERE auction_id=? AND version=?`; 0 linhas = colisão) e `BID/persistence/BidRepository.java` (`findById(bid_id)`, `save` apenas INSERT) (depende de T008)
- [X] T010 [P] Criar `BID/config/KafkaConfig.java`: producer `acks=all`, `enable.idempotence=true`; consumer group `bid-processors`, `enable.auto.commit=false`, `AckMode.MANUAL`; container com concorrência = 6 (nº de partições, cada partição sequencial); serialização JSON do `BidMessage`
- [X] T011 [P] Criar `BID/config/WebSocketConfig.java`: endpoint STOMP `/ws`, broker em memória com destinos `/topic` e `/user/queue`, principal do usuário via `DefaultHandshakeHandler.determinePrincipal`, lendo o atributo de requisição `userId` (gravado pelo `JwtAuthFilter`; nos testes de US1, por um `TestUserFilter` em `BIDT` que copia o header `X-User-Id` para o atributo); o token no handshake é aceito em `Authorization: Bearer` ou, para browsers, em `?access_token=`, conforme `contracts/websocket.md`
- [X] T012 [P] Criar `BID/observability/BidLogger.java` (log JSON com MDC: `auction_id`, `bid_id`, `user_id`, `risk_tier`, `decision`, `reason`, `sequence`, `latency_ms`) e `BID/observability/BidMetrics.java` (Micrometer: `bids.received`, `bids.decided{decision,reason}`, `bids.latency` histograma, `bids.version_collisions`); criar `src/main/resources/logback-spring.xml` com `LogstashEncoder`

**Checkpoint**: fundação pronta.

---

## Phase 3: User Story 1 — Submeter lance e receber resultado (P1) 🎯 MVP

**Goal**: `POST /bids` → 202; consumer decide em ordem de offset, grava `bids` + `auctions` (lock otimista) na mesma transação, envia resultado ao autor por WebSocket e broadcast do novo líder; `bid_id` repetido devolve o resultado original.

**Independent Test** (executado na Phase 5): lances via HTTP, com `TestUserFilter` no lugar do `JwtAuthFilter`, para o leilão seed; cenários 1, 2, 3, 4 e regras extras (quickstart itens 1, 2, 3, 4, 7, 8).

### Implementation for US1

- [X] T013 [P] [US1] Implementar `BID/domain/BidProcessor.java`: função pura (sem Spring) `decide(BidCommand, Optional<Auction> estado, Instant logAppendTime)` → `BidDecisionOutcome` seguindo exatamente a ordem 2→8 do data-model.md (bid_id duplicado é tratado antes, no `BidTransactionService`)
- [X] T014 [P] [US1] Implementar `BID/pipeline/BidPipeline.java` (interface `submit(BidCommand, RiskTier)`), `BID/pipeline/KafkaBidPipeline.java` (única implementação; key=`auction_id`; headers `bid_id`, `risk_tier`; valor JSON conforme `contracts/kafka-message.md`) e `BID/pipeline/BidMessage.java`
- [X] T015 [P] [US1] Implementar `BID/api/BidRequest.java`/`BidReceived.java` (DTOs conforme `contracts/openapi.yaml`, validação Bean Validation: UUIDs, `valor` > 0 com 2 casas) e `BID/api/BidController.java` (`POST /bids`: lê o atributo de requisição `userId` (`@RequestAttribute`), consulta `risk_tier` do leilão (desconhecido = `normal`), chama `BidPipeline.submit`, responde 202; 503 em falha de publicação; incrementa `bids.received`)
- [X] T016 [US1] Implementar `BID/domain/BidTransactionService.java` (`@Transactional`, uma transação por lance): primeiro `BidRepository.findById(bid_id)` — se existir, reconstruir o `BidResult` original (`decision`, `reason`, `sequence`, `duplicate=true`) e parar (FR-004, regra 1); senão carrega leilão, chama `BidProcessor`, insere linha em `bids` (exceto AUCTION_NOT_FOUND; `DataIntegrityViolationException` no INSERT = duplicata concorrente, R4) e, se aceito, executa `AuctionRepository.updateLeader` com `version`; 0 linhas → rollback, incrementa `bids.version_collisions`, loga anomalia e relança para reprocessar (depende de T009, T013)
- [X] T017 [US1] Implementar `BID/consumer/BidListener.java` (`@KafkaListener` tópico `bids`, usa `ConsumerRecord.timestamp()` como LogAppendTime e `offset`/`partition` como sequence/partition; lê o header `risk_tier` e o coloca no MDC e no `BidLogger`; colisão de versão: reprocessar até 3 vezes relendo o estado e, esgotadas as tentativas, logar erro, **não** dar ack e relançar para o `DefaultErrorHandler` (backoff fixo de 1 s) reentregar o mesmo offset; `Acknowledgment.acknowledge()` somente após commit; registra métricas) (depende de T016)
- [X] T018 [US1] Implementar `BID/realtime/ResultNotifier.java` (envio do `BidResult` ao autor em `/user/queue/bid-results`, formato de `contracts/websocket.md`; em duplicata reenvia o resultado original com `duplicate:true`) e `BID/realtime/LeaderBroadcaster.java` (`/topic/auctions/{auction_id}` com `highest_bid`, `highest_user`, `version`, só para lance aceito e não duplicado, após o commit); acionar a partir do `BidListener` após o commit
- [X] T019 [US1] Criar `load-tests/last-seconds.js` (k6: N usuários enviam valores crescentes ao mesmo `auction_id`; comentário no topo com a verificação SQL final: `highest_bid` = maior valor válido, zero `bid_id` duplicado, `sequence` crescente — R12) e `load-tests/seed.sql` (leilão de demonstração: `starting_price=100`, `risk_tier='hot'`, vendedor `s1`, `end_time` distante, `status='ativo'`)

**Checkpoint**: código de US1 (incluindo idempotência) completo; validação na Phase 5.

---

## Phase 4: User Story 2 — Autenticação e rate limit na borda de entrada (P2)

**Goal**: Filtros servlet da aplicação rejeitam requisições sem JWT válido (401) e acima do limite (429) antes de chegarem ao pipeline; expõe o `sub` como atributo `userId`.

**Independent Test** (executado na Phase 5): sem token → 401 e nada no tópico; rajada > 20 req/s → 429 (quickstart itens 5 e 6).

### Implementation for US2

- [X] T020 [P] [US2] Implementar `GW/JwtAuthFilter.java` (servlet `OncePerRequestFilter`; JWT HS256 com segredo local via env `JWT_SECRET`; `user_id`=claim `sub`; grava o `sub` no atributo de requisição `userId` (nunca lê `userId`/`X-User-Id` vindos do cliente); aceita o token em `Authorization: Bearer` e, só em `/ws`, em `access_token`; documentar como simulação, Princípio VI)
- [X] T021 [P] [US2] Implementar `GW/RateLimitFilter.java` (Bucket4j em memória, por `user_id` e fallback por IP; padrão 20 req/s configurável `ratelimit.requests-per-second`; resposta 429; ordenado após o JwtAuthFilter)
- [X] T022 [US2] Registrar `JwtAuthFilter` e `RateLimitFilter` em `BID/config/SecurityFilterConfig.java` (`FilterRegistrationBean`, escopo `/bids` e `/ws/**`, ordem: JWT antes de rate limit) e criar `load-tests/gen-jwt.sh` para gerar JWTs de teste (`u1`, `u2`, vendedor `s1`)

**Checkpoint**: código de US1 e US2 completo; pronto para a bateria de testes.

---

## Phase 5: Tests (após toda a implementação)

**Purpose**: escrever os testes contra o código pronto. Derive os casos das regras da spec e do data-model, não do que o código faz — principalmente nas bordas de `BidProcessor`: valor igual ao `starting_price` é aceito, igual ao `highest_bid` é rejeitado, `logAppendTime == end_time` é `AUCTION_CLOSED`.

- [X] T023 Criar `BIDT/integration/IntegrationTestBase.java` com Testcontainers (PostgreSQL + Kafka com tópico `bids` de 6 partições e `LogAppendTime`) e helpers para inserir leilões e publicar mensagens; criar também `BIDT/integration/TestUserFilter.java` (copia o header `X-User-Id` para o atributo `userId`, usado no lugar do `JwtAuthFilter` nos testes de US1) (depende de T005, T006)
- [X] T024 [P] [US1] Testes unitários `BIDT/domain/BidProcessorTest.java` (ordem de regras do data-model: AUCTION_NOT_FOUND; AUCTION_CLOSED por `status=encerrado` e por `logAppendTime >= end_time`, aceito se `<`; SELLER_CANNOT_BID; ALREADY_LEADING; BELOW_STARTING_PRICE com `valor < starting_price`, igual é aceito; BELOW_CURRENT_BID com `valor <= highest_bid`, inclusive igual; aceite válido) e `BIDT/domain/BidTransactionServiceTest.java` (Mockito: `bid_id` existente → resultado original com `duplicate=true`, sem chamar `updateLeader` nem inserir; violação de PK no INSERT tratada como duplicata; `updateLeader` com 0 linhas → exceção de colisão)
- [X] T025 [P] [US1] Testes unitários `BIDT/pipeline/KafkaBidPipelineTest.java` (key=auction_id, headers `bid_id` e `risk_tier`; falha de publicação → 503), `BIDT/api/BidControllerTest.java` (@WebMvcTest: 202 com `{bid_id,status:RECEIVED}`; 400 para bid_id não-UUID, valor <= 0, campos ausentes; `user_id` vem do atributo `userId`, nunca do corpo), `BIDT/consumer/BidListenerTest.java` (ack só após commit; retry até 3 vezes em colisão de versão) e `BIDT/realtime/ResultNotifierTest.java` (envio a `/user/queue/bid-results`; broadcast em `/topic/auctions/{id}` só quando aceito e nunca em duplicata)
- [X] T026 [US1] Teste de integração `BIDT/integration/BidFlowIT.java` (depende de T023): cenário 1 (150 sobre 100 → aceito, `version` +1, linha em `bids`); cenário 2 (100 sobre 150 → rejeitado, `version` inalterada, linha rejeitada); regras extras; AUCTION_NOT_FOUND sem linha em `bids`; append-only (UPDATE/DELETE em `bids` falha pelo trigger); idempotência (mesmo `bid_id` reenviado, aceito e rejeitado, em mesmo e outro `auction_id` → uma só linha, estado inalterado, WebSocket recebe resultado original com `duplicate:true`); 2 lances concorrentes no mesmo `auction_id` (valores diferentes e iguais) → `sequence` crescente e igual aceita só o primeiro do log
- [X] T027 [P] [US2] Teste unitário `GWT/JwtAuthFilterTest.java` (token ausente, malformado, assinatura inválida, expirado → 401 sem invocar a cadeia; válido → repassa a cadeia com `X-User-Id`=`sub`, ignorando qualquer `X-User-Id`/`userId` enviado pelo cliente)
- [X] T028 [P] [US2] Teste unitário `GWT/RateLimitFilterTest.java` (bucket por `user_id`/IP; acima do limite → 429; usuários independentes; reposição após a janela)

**Checkpoint**: todos os testes escritos e passando. Se um teste revelar bug de concorrência ou de ack, corrija o código e rode de novo.

---

## Phase 6: Polish & Cross-Cutting

- [X] T029 Executar `./mvnw verify` e garantir que o gate JaCoCo (linhas ≥ 0.80, meta 81%) passa (ajustar testes ou exclusões legítimas, somente config/main, em `pom.xml`); em seguida validar manualmente o roteiro de `specs/001-submissao-de-lance/quickstart.md` (itens 1–9, incluindo `docker compose kill app` / `up -d` durante a carga) e o k6 de T019

---

## Dependencies & Execution Order

- Setup (T001–T004) → Foundational (T005–T012) → US1 (T013–T019) e US2 (T020–T022) → Tests (T023–T028) → Polish (T029).
- **US1** depende só da Foundational. **US2** é independente de US1 no código (pacote `gateway`) e pode rodar em paralelo.
- Nenhum teste automatizado é escrito antes de a implementação (T001–T022) estar concluída.
- Ordem interna US1: T013, T014, T015 [P] → T016 → T017 → T018 → T019.
- Ordem interna Tests: T023 → T026; T024, T025, T027, T028 independentes entre si.

## Parallel Opportunities

- Setup: T003 e T004 em paralelo (após T001–T002 se o build for necessário).
- Foundational: T007, T008, T010, T011, T012 em paralelo (T009 após T008).
- US1: T013, T014, T015 em paralelo. US2 (T020, T021) em paralelo com US1.
- Tests: T024, T025, T027, T028 em paralelo; T023 antes de T026.

### Exemplo: User Story 1
```text
Task: "Implementar BidProcessor em src/main/java/com/auction/bid/domain/BidProcessor.java"
Task: "Implementar BidPipeline/KafkaBidPipeline"
Task: "Implementar BidController e DTOs"
```

## Implementation Strategy

1. **Implementação primeiro**: Setup → Foundational → US1 → US2, sem testes automatizados no caminho.
2. **Testes depois**: Phase 5; rode `./mvnw verify` cedo nela para ver a cobertura.
3. **Validação final**: T029 — gate JaCoCo ≥ 80%, quickstart itens 1–9 e k6 (T019).
4. **Corte de MVP**: se precisar entregar menos, pare após US1 e seus testes (T013–T019, T023–T026) e deixe US2 (T020–T022, T027–T028) para depois.

## Notes

- Princípio I: nunca processar o mesmo `auction_id` em paralelo (concorrência do listener = nº de partições, uma thread por partição).
- `timestamp_cliente` é apenas informativo; decisões usam `ConsumerRecord.timestamp()`.
- O consumer nunca altera `status` do leilão (R6).
