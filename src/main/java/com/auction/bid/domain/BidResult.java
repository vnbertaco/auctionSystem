package com.auction.bid.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * Resultado final de um lance, enviado ao autor pelo WebSocket (contracts/websocket.md).
 * {@code sequence} é nulo em AUCTION_NOT_FOUND (lance sem linha em bids).
 */
public record BidResult(
        @JsonProperty("bid_id") UUID bidId,
        @JsonProperty("auction_id") UUID auctionId,
        Decision decision,
        Reason reason,
        Long sequence,
        boolean duplicate) {

    public BidResult asDuplicate() {
        return new BidResult(bidId, auctionId, decision, reason, sequence, true);
    }
}
