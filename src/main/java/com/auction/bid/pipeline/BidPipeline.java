package com.auction.bid.pipeline;

import com.auction.bid.domain.BidCommand;
import com.auction.bid.domain.RiskTier;

/** Abstração de roteamento de lances (Princípio IV). Hoje só existe a implementação Kafka. */
public interface BidPipeline {

    void submit(BidCommand command, RiskTier riskTier);
}
