CREATE TABLE auctions (
    auction_id     uuid PRIMARY KEY,
    item_name      varchar(200) NOT NULL,
    seller_id      varchar(64)  NOT NULL,
    risk_tier      varchar(10)  NOT NULL CHECK (risk_tier IN ('normal', 'hot')),
    starting_price numeric(14,2) CHECK (starting_price > 0),
    end_time       timestamptz  NOT NULL,
    highest_bid    numeric(14,2) NULL,
    highest_user   varchar(64)  NULL,
    version        bigint       NOT NULL DEFAULT 0,
    status         varchar(10)  CHECK (status IN ('ativo', 'encerrado'))
);

CREATE TABLE bids (
    bid_id            uuid PRIMARY KEY,
    auction_id        uuid NOT NULL REFERENCES auctions (auction_id),
    user_id           varchar(64) NOT NULL,
    valor             numeric(14,2) CHECK (valor > 0),
    timestamp_cliente timestamptz,
    log_append_time   timestamptz,
    partition         int,
    sequence          bigint,
    decision          varchar(10) CHECK (decision IN ('aceito', 'rejeitado')),
    reason            varchar(40) NULL,
    processed_at      timestamptz
);

CREATE INDEX idx_bids_auction_sequence ON bids (auction_id, sequence);
