package com.auction.bid.observability;

import com.auction.bid.domain.Decision;
import com.auction.bid.domain.Reason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class BidMetrics {

    private final MeterRegistry registry;
    private final Counter received;
    private final Counter collisions;

    public BidMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.received = Counter.builder("bids.received").description("Lances recebidos pelo HTTP").register(registry);
        this.collisions = Counter.builder("bids.version_collisions")
                .description("Colisões de version no lock otimista").register(registry);
    }

    public void received() {
        received.increment();
    }

    public void decided(Decision decision, Reason reason) {
        Counter.builder("bids.decided")
                .tag("decision", decision.value())
                .tag("reason", reason == null ? "none" : reason.name())
                .register(registry)
                .increment();
    }

    public void latency(Duration duration) {
        Timer.builder("bids.latency")
                .description("Tempo de processamento de um lance no consumer")
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    public void versionCollision() {
        collisions.increment();
    }
}
