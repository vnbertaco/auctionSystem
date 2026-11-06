package com.auction.bid.domain;

import java.util.UUID;

/** Colisão de lock otimista (Princípio III): UPDATE de auctions afetou 0 linhas. */
public class VersionCollisionException extends RuntimeException {

    public VersionCollisionException(UUID auctionId, long expectedVersion) {
        super("colisão de versão no leilão " + auctionId + " (versão esperada " + expectedVersion + ")");
    }
}
