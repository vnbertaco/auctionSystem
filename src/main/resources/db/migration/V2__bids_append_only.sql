-- Princípio VII: histórico de lances é imutável; a aplicação nunca emite UPDATE/DELETE em bids.
CREATE OR REPLACE FUNCTION bids_block_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'bids é append-only: % bloqueado', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER bids_append_only
    BEFORE UPDATE OR DELETE ON bids
    FOR EACH ROW EXECUTE FUNCTION bids_block_mutation();
