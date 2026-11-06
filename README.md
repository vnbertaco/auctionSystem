# bid-service

Serviço de submissão de lances para leilões concorrentes. Cada lance é recebido por HTTP, publicado no Kafka e processado de forma sequencial por partição (um *single writer* por leilão), o que garante que o maior lance vença mesmo sob concorrência. O resultado final chega ao cliente por WebSocket (STOMP).

## Sumário

- [Arquitetura](#arquitetura)
- [Pré-requisitos](#pré-requisitos)
- [Subindo o ambiente](#subindo-o-ambiente)
- [Autenticação de teste](#autenticação-de-teste)
- [Exemplos de uso](#exemplos-de-uso)
  - [1. Submeter um lance](#1-submeter-um-lance)
  - [2. Receber o resultado por WebSocket](#2-receber-o-resultado-por-websocket)
  - [3. Acompanhar o líder do leilão](#3-acompanhar-o-líder-do-leilão)
  - [4. Reenviar um lance (idempotência)](#4-reenviar-um-lance-idempotência)
  - [5. Erros comuns](#5-erros-comuns)
- [Referência da API](#referência-da-api)
- [Configuração](#configuração)
- [Testes](#testes)
- [Testes de carga](#testes-de-carga)
- [Observabilidade](#observabilidade)
- [Estrutura do projeto](#estrutura-do-projeto)

## Arquitetura

```
cliente ──POST /bids──▶ JwtAuthFilter ─▶ RateLimitFilter ─▶ BidController
                                                              │ (202 Accepted)
                                                              ▼
                                                     Kafka: tópico `bids`
                                                     key = auction_id (6 partições)
                                                              │
                                                              ▼
                                                  BidListener (1 thread por partição)
                                                  transação: lê leilão, valida, grava bids
                                                              │
                                  ┌───────────────────────────┴──────────────────────┐
                                  ▼                                                  ▼
                 /user/queue/bid-results (WebSocket)              /topic/auctions/{id} (WebSocket)
                 resultado ao autor do lance                      novo líder, após commit
```

- **Idempotência:** o `bid_id` é gerado pelo cliente e é a chave primária de `bids`. Reenvios devolvem o resultado original com `duplicate: true`.
- **Ordem:** a chave Kafka é o `auction_id`, então todos os lances de um leilão caem na mesma partição e são processados em ordem.
- **Log append-only:** `UPDATE` e `DELETE` em `bids` são bloqueados por trigger no banco.

## Pré-requisitos

- Docker e Docker Compose (para subir a stack completa), **ou**
- JDK 21 e um PostgreSQL 17 + Kafka 3.9 acessíveis (para rodar a aplicação localmente)
- `openssl` (para gerar tokens de teste)
- Opcional: `curl`, `uuidgen`, Node 22+ (para `scripts/ouvir-eventos.mjs`), [k6](https://k6.io/) para os testes de carga

## Subindo o ambiente

```bash
# 1. Defina o segredo usado para validar os JWTs (mínimo de 32 bytes para HS256)
export JWT_SECRET='troque-por-um-segredo-com-pelo-menos-32-bytes'

# 2. Sobe PostgreSQL, Kafka (com criação do tópico `bids`) e a aplicação
docker compose up -d --build

# 3. Acompanhe os logs até a aplicação subir
docker compose logs -f app
```

As migrações do Flyway (`V1__schema.sql`, `V2__bids_append_only.sql`) rodam automaticamente na inicialização.

### Criando um leilão de demonstração

O arquivo `load-tests/seed.sql` cria o leilão `6f1c2a3e-1111-4a6b-9c0d-000000000001` (preço inicial 100,00, tier `hot`, encerra em 30 dias):

```bash
docker compose exec -T postgres psql -U auction -d auction < load-tests/seed.sql
```

Para criar outros leilões, insira linhas na tabela `auctions`. Campos relevantes:

| Campo           | Descrição                                                   |
|-----------------|-------------------------------------------------------------|
| `auction_id`    | UUID do leilão                                              |
| `seller_id`     | Vendedor; ele **não pode** dar lances no próprio leilão     |
| `risk_tier`     | `normal` ou `hot`; a classe é propagada no header Kafka `risk_tier` e nos logs |
| `starting_price`| Lance mínimo aceito                                         |
| `end_time`      | Lances após esse instante são rejeitados (`AUCTION_CLOSED`) |

### Rodando a aplicação fora do Docker

```bash
# Banco e Kafka precisam estar acessíveis; ajuste as variáveis conforme necessário
export DB_URL=jdbc:postgresql://localhost:5432/auction
export DB_USER=auction
export DB_PASSWORD=auction
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export JWT_SECRET='troque-por-um-segredo-com-pelo-menos-32-bytes'

./mvnw spring-boot:run
```

## Autenticação de teste

A autenticação é uma **simulação**: o serviço valida um JWT HS256 assinado com `JWT_SECRET`. O identificador do usuário é a claim `sub`. O cliente nunca informa o `user_id` no corpo da requisição.

O script `load-tests/gen-jwt.sh` gera tokens válidos por 1 hora:

```bash
export JWT_SECRET='troque-por-um-segredo-com-pelo-menos-32-bytes'

TOKEN_U1=$(./load-tests/gen-jwt.sh u1)
TOKEN_U2=$(./load-tests/gen-jwt.sh u2)
TOKEN_S1=$(./load-tests/gen-jwt.sh s1)   # vendedor do leilão de demonstração

echo "$TOKEN_U1"
```

> O `JWT_SECRET` do script deve ser **idêntico** ao configurado na aplicação.

## Exemplos de uso

Todos os exemplos usam estas variáveis de ambiente:

```bash
export BASE_URL=http://localhost:8080
export AUCTION_ID=6f1c2a3e-1111-4a6b-9c0d-000000000001
export TOKEN_U1=$(./load-tests/gen-jwt.sh u1)
export TOKEN_U2=$(./load-tests/gen-jwt.sh u2)
```

### 1. Submeter um lance

O `bid_id` deve ser um UUID novo, gerado pelo cliente para cada lance:

```bash
BID_ID=$(uuidgen)

curl -i -X POST "$BASE_URL/bids" \
  -H "Authorization: Bearer $TOKEN_U1" \
  -H "Content-Type: application/json" \
  -d "{
        \"auction_id\": \"$AUCTION_ID\",
        \"valor\": 150.00,
        \"bid_id\": \"$BID_ID\",
        \"timestamp_cliente\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"
      }"
```

Resposta `202 Accepted`:

```json
{
  "bid_id": "3f9c1e2a-8b7d-4c6e-9a1f-2d3e4f5a6b7c",
  "status": "RECEIVED"
}
```

A resposta **não** informa se o lance foi aceito. Isso só é conhecido após o processamento e chega pelo WebSocket (exemplo 2).

`timestamp_cliente` é opcional e serve apenas como informação. O horário oficial de chegada é o do broker Kafka.

Formato aceito para `timestamp_cliente`: ISO-8601 em UTC, por exemplo `2026-11-03T12:00:00Z` (com fração opcional, como `2026-11-03T12:00:00.123Z`). Outros formatos, como `03/11/2026 12:00`, retornam `400`.

### 2. Receber o resultado por WebSocket

O resultado de cada lance chega por WebSocket, no protocolo STOMP. O script `scripts/ouvir-eventos.mjs` faz todo o protocolo para você (Node 22+, sem dependências):

```bash
TOKEN=$TOKEN_U1 AUCTION_ID=$AUCTION_ID node scripts/ouvir-eventos.mjs
```

Ele conecta em `/ws`, envia o `CONNECT`, assina `/user/queue/bid-results` (resultado do próprio usuário) e, com `AUCTION_ID`, também `/topic/auctions/{id}` (líder). Cada evento sai como JSON. Deixe o script rodando e envie um lance em outro terminal (exemplo 1).

Ao enviar um lance aceito, o usuário recebe:

```json
{
  "recebido_em": "2026-11-05T02:30:32.066Z",
  "destino": "/user/queue/bid-results",
  "evento": {
    "bid_id": "3f9c1e2a-8b7d-4c6e-9a1f-2d3e4f5a6b7c",
    "auction_id": "6f1c2a3e-1111-4a6b-9c0d-000000000001",
    "decision": "aceito",
    "reason": null,
    "sequence": 12345,
    "duplicate": false
  }
}
```

Um lance rejeitado tem `decision: "rejeitado"` e um `reason` (tabela no exemplo 5).

> A entrega é **melhor esforço**: não há SLA de latência. Se a conexão cair, reenvie o mesmo `bid_id` para obter o resultado de novo (exemplo 4).

#### Por que não usar `wscat` direto

O servidor fala STOMP por cima do WebSocket. Cada frame precisa terminar com o byte NUL (`\0`). No teste que fiz, o `wscat` não entregou esse byte de forma confiável: a conexão abriu, mas o servidor nunca respondeu com `CONNECTED`. O script acima envia os frames corretamente.

Cliente em JavaScript, se preferir integrar ao seu código (navegador ou Node com `@stomp/stompjs`):

```javascript
import { Client } from '@stomp/stompjs';

const client = new Client({
  brokerURL: `ws://localhost:8080/ws?access_token=${token}`,
  onConnect: () => {
    client.subscribe('/user/queue/bid-results', (frame) => {
      const resultado = JSON.parse(frame.body);
      console.log(resultado.decision, resultado.reason ?? '', resultado.sequence);
    });
  },
});

client.activate();
```

### 3. Acompanhar o líder do leilão

Qualquer cliente pode assinar o tópico público do leilão. A mensagem chega apenas quando um lance **aceito** é confirmado. Com o script do exemplo 2, basta definir `AUCTION_ID`:

```bash
TOKEN=$TOKEN_U1 AUCTION_ID=$AUCTION_ID node scripts/ouvir-eventos.mjs
```

Quando um lance é aceito, o script imprime um evento com `destino` `/topic/auctions/{id}`. O conteúdo de `evento` é:

```json
{
  "auction_id": "6f1c2a3e-1111-4a6b-9c0d-000000000001",
  "highest_bid": 150.00,
  "highest_user": "u1",
  "version": 7
}
```

### 4. Reenviar um lance (idempotência)

Reenviar o mesmo `bid_id` não gera um novo lance. O serviço devolve o resultado original com `duplicate: true`. Isso é útil para recuperar o resultado após uma queda de conexão ou em caso de retry de rede:

```bash
curl -i -X POST "$BASE_URL/bids" \
  -H "Authorization: Bearer $TOKEN_U1" \
  -H "Content-Type: application/json" \
  -d "{\"auction_id\": \"$AUCTION_ID\", \"valor\": 150.00, \"bid_id\": \"$BID_ID\"}"
```

O resultado chega novamente em `/user/queue/bid-results`, agora com `"duplicate": true`. A tabela `bids` mantém uma única linha para esse `bid_id`.

### 5. Erros comuns

**Sem token ou com token inválido** → `401 Unauthorized`. A requisição não chega ao Kafka.

```bash
curl -i -X POST "$BASE_URL/bids" -H "Content-Type: application/json" -d '{}'
```

**Corpo inválido** → `400 Bad Request`. Ocorre quando o `bid_id` não é UUID, o `valor` é menor ou igual a zero, há mais de 2 casas decimais ou algum campo obrigatório está ausente.

```bash
curl -i -X POST "$BASE_URL/bids" \
  -H "Authorization: Bearer $TOKEN_U1" \
  -H "Content-Type: application/json" \
  -d "{\"auction_id\": \"$AUCTION_ID\", \"valor\": -5, \"bid_id\": \"$(uuidgen)\"}"
```

**Limite de taxa excedido** → `429 Too Many Requests`. O limite padrão é de 20 requisições por segundo por usuário (ou por IP, quando não há token).

**Kafka indisponível** → `503 Service Unavailable` com corpo `{"error": "pipeline_indisponivel"}`.

Motivos de rejeição que aparecem no campo `reason` do WebSocket:

| `reason`             | Quando ocorre                                                    |
|----------------------|------------------------------------------------------------------|
| `BELOW_STARTING_PRICE` | Valor menor que o preço inicial do leilão                      |
| `BELOW_CURRENT_BID`  | Valor não supera o lance atual                                   |
| `SELLER_CANNOT_BID`  | O vendedor tentou dar lance no próprio leilão                    |
| `ALREADY_LEADING`    | O usuário já é o líder e repetiu o lance                         |
| `AUCTION_CLOSED`     | Lance recebido após `end_time`                                   |
| `AUCTION_NOT_FOUND`  | `auction_id` desconhecido (`sequence` é `null`, e não há linha em `bids`) |

## Referência da API

Especificação completa em [`specs/001-submissao-de-lance/contracts/openapi.yaml`](specs/001-submissao-de-lance/contracts/openapi.yaml).

| Método | Caminho                          | Descrição                                          | Autenticação |
|--------|----------------------------------|----------------------------------------------------|--------------|
| POST   | `/bids`                          | Submete um lance (responde `202`)                  | JWT          |
| WS     | `/ws`                            | Endpoint STOMP                                     | JWT (handshake) |
| SUB    | `/user/queue/bid-results`        | Resultado final do lance do próprio usuário        | JWT          |
| SUB    | `/topic/auctions/{auction_id}`   | Novo líder do leilão                               | JWT          |
| GET    | `/actuator/health`               | Saúde da aplicação                                 | Não          |
| GET    | `/actuator/prometheus`           | Métricas no formato Prometheus                     | Não          |

Contratos detalhados:
- [`contracts/openapi.yaml`](specs/001-submissao-de-lance/contracts/openapi.yaml): REST
- [`contracts/websocket.md`](specs/001-submissao-de-lance/contracts/websocket.md): STOMP
- [`contracts/kafka-message.md`](specs/001-submissao-de-lance/contracts/kafka-message.md): mensagem no tópico `bids`

## Configuração

Variáveis de ambiente (valores padrão entre parênteses):

| Variável                  | Padrão                                     | Descrição                                          |
|---------------------------|--------------------------------------------|----------------------------------------------------|
| `DB_URL`                  | `jdbc:postgresql://localhost:5432/auction` | URL JDBC do PostgreSQL                             |
| `DB_USER`                 | `auction`                                  | Usuário do banco                                   |
| `DB_PASSWORD`             | `auction`                                  | Senha do banco                                     |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092`                           | Brokers Kafka                                      |
| `JWT_SECRET`              | *(obrigatório)*                            | Segredo HS256 para validar JWTs (≥ 32 bytes)       |
| `RATELIMIT_RPS`           | `20`                                       | Requisições por segundo por usuário/IP            |
| `SERVER_PORT`             | `8080`                                     | Porta HTTP                                         |

Propriedades internas relevantes em `application.yml`:

- `auction.kafka.topic`: tópico de lances (`bids`)
- `auction.kafka.concurrency`: threads consumidoras, uma por partição (`6`)
- `auction.gateway.enabled`: liga/desliga JWT e rate limit (`true`)

## Testes

```bash
./mvnw verify
```

Executa testes unitários (`*Test`) e de integração com Testcontainers (`*IT`, sobe PostgreSQL e Kafka em contêineres, então requer Docker). O JaCoCo falha o build se a cobertura de linhas ficar abaixo de **80%**. O relatório HTML fica em `target/site/jacoco/index.html`.

Para rodar só os testes unitários de uma classe:

```bash
./mvnw test -Dtest=BidControllerTest
```

## Testes de carga

O script `load-tests/last-seconds.js` (k6) envia lances concorrentes com valores crescentes para o mesmo leilão.

```bash
JWT_SECRET="$JWT_SECRET" AUCTION_ID="$AUCTION_ID" k6 run load-tests/last-seconds.js
```

Ao final, confira a consistência dos dados no banco. Todas as consultas devem retornar `true` ou `0`, conforme o comentário no próprio script:

```sql
-- highest_bid igual ao maior valor aceito
SELECT a.highest_bid = (SELECT max(valor) FROM bids WHERE auction_id = a.auction_id AND decision = 'aceito')
  FROM auctions a WHERE a.auction_id = '6f1c2a3e-1111-4a6b-9c0d-000000000001';

-- nenhum bid_id duplicado (deve ser 0)
SELECT count(*) - count(DISTINCT bid_id) FROM bids
 WHERE auction_id = '6f1c2a3e-1111-4a6b-9c0d-000000000001';
```

Ajuste `VUS` no script para mudar o número de usuários simultâneos (padrão: 100).

## Observabilidade

- **Logs:** em JSON (Logstash encoder), com os campos definidos em FR-010. Cada decisão registra `bid_id`, `auction_id`, `user_id`, `decision`, `reason`, partição, offset e latência.
- **Métricas:** `GET /actuator/prometheus`. Inclui contadores de lances recebidos, decisões e colisões de versão, além da latência de processamento.
- **Saúde:** `GET /actuator/health`.

```bash
curl -s "$BASE_URL/actuator/health"
curl -s "$BASE_URL/actuator/prometheus" | grep -i bid
```

## Estrutura do projeto

```
src/main/java/com/auction/bid/
├── api/            # Controller REST, DTOs de entrada/saída, tratamento de erros, risk tier
├── config/         # Filtros, Kafka, WebSocket
├── consumer/       # BidListener: processamento sequencial por partição
├── domain/         # Regras de negócio (BidTransactionService, BidProcessor)
├── gateway/        # JwtAuthFilter e RateLimitFilter
├── observability/  # Logs estruturados e métricas
├── persistence/    # Entidades JPA e repositórios
├── pipeline/       # Publicação no Kafka
└── realtime/       # Envio de resultados e líder via WebSocket
src/main/resources/db/migration/   # Migrações Flyway
load-tests/                        # k6, seed e geração de JWT
specs/                             # Especificação, plano e contratos
```
