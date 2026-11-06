package com.auction.bid.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Estado do leilão lido antes da decisão (snapshot imutável). */
public record AuctionState(
        UUID auctionId,
        String sellerId,
        RiskTier riskTier,
        BigDecimal startingPrice,
        Instant endTime,
        BigDecimal highestBid,
        String highestUser,
        long version,
        String status) {

    public static final String STATUS_ENCERRADO = "encerrado";

    public boolean hasAcceptedBid() {
        return highestBid != null;
    }
}
