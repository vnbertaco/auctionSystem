# Data Model: Submissão de Lance

## Tabela `auctions` (estado atual, mutável)

| Campo | Tipo | Regras |
|---|---|---|
| `auction_id` | uuid PK | |
| `item_name` | varchar(200) | obrigatório |
| `seller_id` | varchar(64) | obrigatório (FR-003a) |
| `risk_tier` | varchar(10) | `normal` \| `hot`, obrigatório (FR-009) |
| `starting_price` | numeric(14,2) | > 0 |
| `end_time` | timestamptz | obrigatório |
| `highest_bid` | numeric(14,2) | NULL até o primeiro lance aceito |
| `highest_user` | varchar(64) | NULL até o primeiro lance aceito |
| `version` | bigint | inicia em 0; +1 a cada lance aceito |
| `status` | varchar(10) | `ativo` \| `encerrado` |

**Escrita**: só o consumer, via `UPDATE auctions SET highest_bid=?, highest_user=?, version=version+1 WHERE auction_id=? AND version=?`. 0 linhas = colisão (reprocessar).

## Tabela `bids` (histórico, append-only)

| Campo | Tipo | Regras |
|---|---|---|
| `bid_id` | uuid PK | globalmente único (FR-004) |
| `auction_id` | uuid FK → `auctions` | obrigatório; por isso `AUCTION_NOT_FOUND` não gera linha |
| `user_id` | varchar(64) | do JWT |
| `valor` | numeric(14,2) | > 0 |
| `timestamp_cliente` | timestamptz | informativo, nunca usado em decisão |
| `log_append_time` | timestamptz | timestamp do broker |
| `partition` | int | |
| `sequence` | bigint | offset Kafka |
| `decision` | varchar(10) | `aceito` \| `rejeitado` |
| `reason` | varchar(40) | NULL se aceito |
| `processed_at` | timestamptz | |

**Imutabilidade**: trigger `BEFORE UPDATE OR DELETE` lança exceção. Índice em `(auction_id, sequence)`.

## Motivos de rejeição (`reason`)

`BELOW_CURRENT_BID` (≤ `highest_bid`), `BELOW_STARTING_PRICE`, `SELLER_CANNOT_BID`, `ALREADY_LEADING`, `AUCTION_CLOSED`, `AUCTION_NOT_FOUND` (só log/WebSocket, sem linha).

## Ordem das regras no `BidProcessor`

1. `bid_id` já existe → reenviar resultado original, parar (FR-004).
2. Leilão não existe → `AUCTION_NOT_FOUND` (sem linha).
3. `status = encerrado` ou `LogAppendTime >= end_time` → `AUCTION_CLOSED`.
4. `user_id = seller_id` → `SELLER_CANNOT_BID`.
5. `user_id = highest_user` → `ALREADY_LEADING`.
6. Sem lance aceito e `valor < starting_price` → `BELOW_STARTING_PRICE`.
7. Com lance aceito e `valor <= highest_bid` → `BELOW_CURRENT_BID`.
8. Caso contrário aceitar (escrita com `version`).

## Transições do lance

`recebido (202)` → `processado: aceito | rejeitado` → resultado enviado ao autor; se aceito, broadcast do novo líder.
