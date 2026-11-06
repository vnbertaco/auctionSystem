package com.auction.bid.persistence;

import com.auction.bid.domain.AuctionState;
import com.auction.bid.domain.RiskTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Estado atual do leilão (mutável; escrito só pelo consumer via UPDATE com version). */
@Entity
@Table(name = "auctions")
public class AuctionEntity {

    @Id
    @Column(name = "auction_id")
    private UUID auctionId;

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(name = "seller_id", nullable = false)
    private String sellerId;

    @Column(name = "risk_tier", nullable = false)
    private String riskTier;

    @Column(name = "starting_price", precision = 14, scale = 2)
    private BigDecimal startingPrice;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(name = "highest_bid", precision = 14, scale = 2)
    private BigDecimal highestBid;

    @Column(name = "highest_user")
    private String highestUser;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "status", nullable = false)
    private String status;

    protected AuctionEntity() {
    }

    public UUID getAuctionId() {
        return auctionId;
    }

    public String getRiskTier() {
        return riskTier;
    }

    public AuctionState toState() {
        return new AuctionState(auctionId, sellerId, RiskTier.fromValue(riskTier), startingPrice,
                endTime, highestBid, highestUser, version, status);
    }
}
