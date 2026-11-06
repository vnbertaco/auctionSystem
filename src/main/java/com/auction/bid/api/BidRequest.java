package com.auction.bid.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BidRequest(
        @NotNull @JsonProperty("auction_id") UUID auctionId,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 2) BigDecimal valor,
        @NotNull @JsonProperty("bid_id") UUID bidId,
        @JsonProperty("timestamp_cliente") Instant timestampCliente) {
}
