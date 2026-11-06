package com.auction.bid.api;

import com.auction.bid.domain.RiskTier;
import com.auction.bid.persistence.AuctionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Consulta o risk_tier do leilão com cache local curto (R9, TTL 30 s). Desconhecido = normal. */
@Component
public class RiskTierResolver {

    static final Duration TTL = Duration.ofSeconds(30);

    private record Entry(RiskTier tier, Instant expiresAt) {
    }

    private final AuctionRepository auctions;
    private final Clock clock;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    @Autowired
    public RiskTierResolver(AuctionRepository auctions) {
        this(auctions, Clock.systemUTC());
    }

    RiskTierResolver(AuctionRepository auctions, Clock clock) {
        this.auctions = auctions;
        this.clock = clock;
    }

    public RiskTier resolve(UUID auctionId) {
        Instant agora = clock.instant();
        Entry cached = cache.get(auctionId);
        if (cached != null && agora.isBefore(cached.expiresAt())) {
            return cached.tier();
        }
        Optional<String> bruto = auctions.findRiskTier(auctionId);
        if (bruto.isEmpty()) {
            return RiskTier.NORMAL;
        }
        RiskTier tier = RiskTier.fromValue(bruto.get());
        cache.put(auctionId, new Entry(tier, agora.plus(TTL)));
        return tier;
    }
}
