package com.auction.bid.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.UUID;

/** Novo líder após lance aceito; enviado em broadcast após o commit (contracts/websocket.md). */
public record LeaderChange(
        @JsonProperty("auction_id") UUID auctionId,
        @JsonProperty("highest_bid") BigDecimal highestBid,
        @JsonProperty("highest_user") String highestUser,
        long version) {
}
