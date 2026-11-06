# Contrato Kafka: tópico `bids`

- **Config do tópico**: 6 partições; `message.timestamp.type=LogAppendTime`.
- **Key**: `auction_id` (string UUID) → mesmo leilão sempre na mesma partição (Princípio I).
- **Producer**: `acks=all`, `enable.idempotence=true`.
- **Consumer**: um group `bid-processors`, `enable.auto.commit=false`, ack manual após commit da transação.

## Headers
- `bid_id` (correlation ID), `risk_tier` (`normal|hot`).

## Value (JSON)
```json
{
  "bid_id": "uuid",
  "auction_id": "uuid",
  "user_id": "string",
  "valor": 150.00,
  "timestamp_cliente": "2026-11-03T12:00:00Z"
}
```

O horário de chegada ao log vem do `ConsumerRecord.timestamp()` (`LogAppendTime`), nunca do corpo. `sequence` = `offset`.
