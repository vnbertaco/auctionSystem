package com.auction.bid.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BidCommand(
        UUID bidId, //unico de cada requisicao
        UUID auctionId, //id do item 
        String userId,
        BigDecimal valor,
        Instant timestampCliente) {
}
