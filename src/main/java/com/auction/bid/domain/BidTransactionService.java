package com.auction.bid.domain;

import com.auction.bid.observability.BidMetrics;
import com.auction.bid.persistence.AuctionEntity;
import com.auction.bid.persistence.AuctionRepository;
import com.auction.bid.persistence.BidEntity;
import com.auction.bid.persistence.BidRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Uma transação por lance: idempotência por bid_id, decisão, INSERT em bids e,
 * se aceito, UPDATE de auctions com lock otimista. Qualquer falha reverte tudo.
 */
@Service
public class BidTransactionService {

    private final AuctionRepository auctions;
    private final BidRepository bids;
    private final BidMetrics metrics;

    public BidTransactionService(AuctionRepository auctions, BidRepository bids, BidMetrics metrics) {
        this.auctions = auctions;
        this.bids = bids;
        this.metrics = metrics;
    }

    @Transactional
    public BidOutcome process(BidCommand cmd, int partition, long sequence, Instant logAppendTime) {
        Optional<BidEntity> existente = bids.findById(cmd.bidId());
        if (existente.isPresent()) {
            return new BidOutcome(existente.get().toResult().asDuplicate(), Optional.empty());
        }

        Optional<AuctionState> estado = auctions.findById(cmd.auctionId()).map(AuctionEntity::toState);
        BidDecisionOutcome decisao = BidProcessor.decide(cmd, estado, logAppendTime);

        if (decisao.reason() == Reason.AUCTION_NOT_FOUND) {
            // lance sem leilão não tem FK em bids: só o resultado vai ao autor (sem sequence)
            return new BidOutcome(new BidResult(cmd.bidId(), cmd.auctionId(), decisao.decision(),
                    decisao.reason(), null, false), Optional.empty());
        }

        bids.saveAndFlush(BidEntity.create(cmd, decisao, partition, sequence, logAppendTime, Instant.now()));
        BidResult result = new BidResult(cmd.bidId(), cmd.auctionId(), decisao.decision(),
                decisao.reason(), sequence, false);

        if (!decisao.isAccepted()) {
            return new BidOutcome(result, Optional.empty());
        }

        long versaoEsperada = estado.orElseThrow().version();
        int linhas = auctions.updateLeader(cmd.auctionId(), cmd.valor(), cmd.userId(), versaoEsperada);
        if (linhas == 0) {
            metrics.versionCollision();
            throw new VersionCollisionException(cmd.auctionId(), versaoEsperada);
        }
        LeaderChange lider = new LeaderChange(cmd.auctionId(), cmd.valor(), cmd.userId(), versaoEsperada + 1);
        return new BidOutcome(result, Optional.of(lider));
    }
}
