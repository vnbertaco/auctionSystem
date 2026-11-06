package com.auction.bid.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** Resposta 202: lance recebido e publicado; o resultado chega pelo WebSocket. */
public record BidReceived(@JsonProperty("bid_id") UUID bidId, String status) {
}
