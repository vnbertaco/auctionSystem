package com.auction.bid.domain;

public record BidDecisionOutcome(Decision decision, Reason reason) {

    public static BidDecisionOutcome accepted() {
        return new BidDecisionOutcome(Decision.ACEITO, null);
    }

    public static BidDecisionOutcome rejected(Reason reason) {
        return new BidDecisionOutcome(Decision.REJEITADO, reason);
    }

    public boolean isAccepted() {
        return decision == Decision.ACEITO;
    }
}
