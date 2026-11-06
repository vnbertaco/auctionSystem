# Contrato WebSocket (STOMP)

- **Endpoint**: `/ws` (handshake com `Authorization: Bearer <JWT>` ou, para browsers, `?access_token=<JWT>`, validado pelo `JwtAuthFilter`; `user` = claim `sub`).
- **Garantia**: melhor esforço (spec, Suposições). Sem SLA de latência.

## `SUBSCRIBE /user/queue/bid-results` — resultado ao autor (FR-006a)

```json
{
  "bid_id": "uuid",
  "auction_id": "uuid",
  "decision": "aceito | rejeitado",
  "reason": "BELOW_CURRENT_BID | BELOW_STARTING_PRICE | SELLER_CANNOT_BID | ALREADY_LEADING | AUCTION_CLOSED | AUCTION_NOT_FOUND | null",
  "sequence": 12345,
  "duplicate": false
}
```

`duplicate: true` quando for reenvio do resultado original de um `bid_id` já processado (FR-004). `sequence` é nulo em `AUCTION_NOT_FOUND`.

## `SUBSCRIBE /topic/auctions/{auction_id}` — novo líder (FR-006)

Enviado só após a escrita do lance aceito ser confirmada.

```json
{ "auction_id": "uuid", "highest_bid": 150.00, "highest_user": "user-1", "version": 7 }
```
