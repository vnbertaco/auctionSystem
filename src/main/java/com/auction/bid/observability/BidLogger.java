package com.auction.bid.observability;

import com.auction.bid.domain.BidResult;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** Log estruturado (JSON via LogstashEncoder) com os campos de FR-010 / Princípio V. */
@Component
public class BidLogger {

    private static final Logger LOG = LoggerFactory.getLogger(BidLogger.class);

    public void decision(BidResult result, String userId, String riskTier, long latencyMs) {
        withContext(result, userId, riskTier, latencyMs, () ->
                LOG.info("bid_decided"));
    }

    public void duplicate(BidResult result, String userId, String riskTier) {
        withContext(result, userId, riskTier, null, () ->
                LOG.info("bid_duplicate_reemitted"));
    }

    public void versionCollision(String auctionId, String bidId, int attempt) {
        MDC.put("auction_id", auctionId);
        MDC.put("bid_id", bidId);
        try {
            LOG.warn("version_collision attempt={}", attempt);
        } finally {
            MDC.remove("auction_id");
            MDC.remove("bid_id");
        }
    }

    public void failure(String auctionId, String bidId, String userId, Throwable error) {
        MDC.put("auction_id", auctionId);
        MDC.put("bid_id", bidId);
        MDC.put("user_id", userId);
        try {
            LOG.error("bid_processing_failed", error);
        } finally {
            MDC.remove("auction_id");
            MDC.remove("bid_id");
            MDC.remove("user_id");
        }
    }

    private void withContext(BidResult result, String userId, String riskTier, Long latencyMs, Runnable log) {
        MDC.put("auction_id", String.valueOf(result.auctionId()));
        MDC.put("bid_id", String.valueOf(result.bidId()));
        MDC.put("user_id", userId);
        MDC.put("risk_tier", riskTier);
        MDC.put("decision", result.decision().value());
        MDC.put("reason", Optional.ofNullable(result.reason()).map(Enum::name).orElse("none"));
        MDC.put("sequence", String.valueOf(result.sequence()));
        if (latencyMs != null) {
            MDC.put("latency_ms", String.valueOf(latencyMs));
        }
        try {
            log.run();
        } finally {
            for (String key : new String[]{"auction_id", "bid_id", "user_id", "risk_tier", "decision",
                    "reason", "sequence", "latency_ms"}) {
                MDC.remove(key);
            }
        }
    }
}
