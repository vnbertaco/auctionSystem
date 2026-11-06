# Quickstart: validação ponta a ponta

## Pré-requisitos
Docker + Docker Compose, JDK 21, k6, `wscat` (ou cliente STOMP equivalente).

## Subir o ambiente
```bash
docker compose up -d --build   # app, kafka, postgres
```
Cadastre um leilão de teste (seed/Flyway ou SQL manual): `starting_price=100`, `risk_tier=hot`, `end_time` no futuro. Gere dois JWTs de teste (usuários `u1`, `u2`) e um para o vendedor `s1`.

## Cenários (mapeiam para os cenários da spec)
1. **Lance válido** (cenário 1): `POST /bids` com `u1` e `valor=150` → `202`; no WebSocket `u1` recebe `aceito`, `/topic/auctions/{id}` recebe `highest_bid=150`.
2. **Lance baixo** (cenário 2): `u2` envia `100` → resultado `rejeitado/BELOW_CURRENT_BID`; `auctions.version` inalterada.
3. **Concorrência** (cenário 3): `load-tests/` (k6) com N usuários no mesmo leilão → `highest_bid` = maior valor válido; todos os lances em `bids`; `sequence` crescente.
4. **Duplicata** (cenário 4): reenviar o mesmo `bid_id` → `duplicate: true` com o resultado original; `SELECT count(*) FROM bids WHERE bid_id=...` = 1.
5. **Sem token** (cenário 5): `401` pelo `JwtAuthFilter`; nada chega ao tópico.
6. **Rate limit** (cenário 6): rajada acima de 20 req/s → `429`.
7. **Regras extras**: vendedor → `SELLER_CANNOT_BID`; líder repetindo → `ALREADY_LEADING`; `auction_id` desconhecido → `AUCTION_NOT_FOUND` sem linha em `bids`; lance após `end_time` → `AUCTION_CLOSED`.
8. **Append-only**: `UPDATE bids ...` / `DELETE FROM bids` falha pelo trigger.
9. **Falha do consumer**: matar `bid-service` durante a carga e reiniciar → sem `bid_id` duplicado, nenhum lance perdido.

## Testes e cobertura
```bash
./mvnw verify   # unitários + integração (Testcontainers) + JaCoCo (falha se cobertura < 80%)
```

## Observabilidade
Logs JSON do `bid-service` (campos de FR-010) e métricas em `/actuator/prometheus`.
