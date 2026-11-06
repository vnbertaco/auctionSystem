package com.auction.bid.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Regras de negócio puras (sem Spring). Ordem das regras: data-model.md, passos 2 a 8.
 * O passo 1 (bid_id já processado) é tratado em {@link BidTransactionService}.
 */
public final class BidProcessor {

    private BidProcessor() {
    }

    public static BidDecisionOutcome decide(BidCommand cmd, Optional<AuctionState> estado, Instant logAppendTime) {
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(logAppendTime, "logAppendTime");

        if (estado.isEmpty()) {
            return BidDecisionOutcome.rejected(Reason.AUCTION_NOT_FOUND);
        }
        AuctionState leilao = estado.get();

        if (AuctionState.STATUS_ENCERRADO.equals(leilao.status())
                || !logAppendTime.isBefore(leilao.endTime())) {
            return BidDecisionOutcome.rejected(Reason.AUCTION_CLOSED);
        }
        if (cmd.userId().equals(leilao.sellerId())) {
            return BidDecisionOutcome.rejected(Reason.SELLER_CANNOT_BID);
        }
        if (leilao.highestUser() != null && cmd.userId().equals(leilao.highestUser())) {
            return BidDecisionOutcome.rejected(Reason.ALREADY_LEADING);
        }
        if (!leilao.hasAcceptedBid()) {
            if (cmd.valor().compareTo(leilao.startingPrice()) < 0) {
                return BidDecisionOutcome.rejected(Reason.BELOW_STARTING_PRICE);
            }
        } else if (cmd.valor().compareTo(leilao.highestBid()) <= 0) {
            return BidDecisionOutcome.rejected(Reason.BELOW_CURRENT_BID);
        }
        return BidDecisionOutcome.accepted();
    }
}
