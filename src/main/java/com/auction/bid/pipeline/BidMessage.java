package com.auction.bid.pipeline;

import com.auction.bid.domain.BidCommand;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Valor da mensagem no tópico bids (contracts/kafka-message.md). */
public record BidMessage(
        @JsonProperty("bid_id") UUID bidId,
        @JsonProperty("auction_id") UUID auctionId,
        @JsonProperty("user_id") String userId,
        BigDecimal valor,
        @JsonProperty("timestamp_cliente") Instant timestampCliente) {

    public static BidMessage from(BidCommand cmd) {
        return new BidMessage(cmd.bidId(), cmd.auctionId(), cmd.userId(), cmd.valor(), cmd.timestampCliente());
    }

    public BidCommand toCommand() {
        return new BidCommand(bidId, auctionId, userId, valor, timestampCliente);
    }
}
