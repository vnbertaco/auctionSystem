package com.auction.bid.persistence;

import com.auction.bid.domain.BidCommand;
import com.auction.bid.domain.BidDecisionOutcome;
import com.auction.bid.domain.BidResult;
import com.auction.bid.domain.Decision;
import com.auction.bid.domain.Reason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Linha do histórico de lances (append-only, Princípio VII). Sem setters públicos:
 * só a fábrica {@link #create} monta a entidade. {@link Persistable} faz o save
 * emitir INSERT (persist) em vez de SELECT + merge.
 */
@Entity
@Table(name = "bids")
public class BidEntity implements Persistable<UUID> {

    @Id
    @Column(name = "bid_id")
    private UUID bidId;

    @Column(name = "auction_id", nullable = false)
    private UUID auctionId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "valor", nullable = false, precision = 14, scale = 2)
    private BigDecimal valor;

    @Column(name = "timestamp_cliente")
    private Instant timestampCliente;

    @Column(name = "log_append_time")
    private Instant logAppendTime;

    @Column(name = "partition")
    private int partition;

    @Column(name = "sequence", nullable = false)
    private long sequence;

    @Column(name = "decision", nullable = false)
    private String decision;

    @Column(name = "reason")
    private String reason;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Transient
    private boolean newEntity = true;

    protected BidEntity() {
    }

    private BidEntity(UUID bidId, UUID auctionId, String userId, BigDecimal valor, Instant timestampCliente,
                      Instant logAppendTime, int partition, long sequence, BidDecisionOutcome outcome,
                      Instant processedAt) {
        this.bidId = bidId;
        this.auctionId = auctionId;
        this.userId = userId;
        this.valor = valor;
        this.timestampCliente = timestampCliente;
        this.logAppendTime = logAppendTime;
        this.partition = partition;
        this.sequence = sequence;
        this.decision = outcome.decision().value();
        this.reason = outcome.reason() == null ? null : outcome.reason().name();
        this.processedAt = processedAt;
    }

    public static BidEntity create(BidCommand cmd, BidDecisionOutcome outcome, int partition, long sequence,
                                   Instant logAppendTime, Instant processedAt) {
        return new BidEntity(cmd.bidId(), cmd.auctionId(), cmd.userId(), cmd.valor(), cmd.timestampCliente(),
                logAppendTime, partition, sequence, outcome, processedAt);
    }

    public BidResult toResult() {
        return new BidResult(bidId, auctionId, Decision.fromValue(decision),
                reason == null ? null : Reason.valueOf(reason), sequence, false);
    }

    @Override
    public UUID getId() {
        return bidId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.newEntity = false;
    }
}
