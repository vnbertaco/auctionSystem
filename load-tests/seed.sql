-- Leilão de demonstração (quickstart / k6). Executar no banco após o Flyway.
INSERT INTO auctions (auction_id, item_name, seller_id, risk_tier, starting_price, end_time, highest_bid, highest_user, version, status)
VALUES ('6f1c2a3e-1111-4a6b-9c0d-000000000001', 'Item de demonstração', 's1', 'hot', 100.00,
        now() + interval '30 days', NULL, NULL, 0, 'ativo')
ON CONFLICT (auction_id) DO NOTHING;

INSERT INTO auctions (auction_id, item_name, seller_id, risk_tier, starting_price, end_time, highest_bid, highest_user, version, status)
VALUES ('c760652d-5389-4550-a89f-2472453c32a6', 'Action Comics nº 1, de 1939', 's1', 'hot', 1000000.00,
        now() + interval '30 days', NULL, NULL, 0, 'ativo')
ON CONFLICT (auction_id) DO NOTHING;

