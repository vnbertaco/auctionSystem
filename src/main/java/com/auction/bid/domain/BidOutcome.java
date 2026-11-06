package com.auction.bid.domain;

import java.util.Optional;

/** Resultado do processamento de um lance dentro da transação, com o líder novo, se houver. */
public record BidOutcome(BidResult result, Optional<LeaderChange> leaderChange) {
}
