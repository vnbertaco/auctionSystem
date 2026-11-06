package com.auction.bid.consumer;

import com.auction.bid.domain.BidCommand;
import com.auction.bid.domain.BidOutcome;
import com.auction.bid.domain.BidTransactionService;
import com.auction.bid.domain.RiskTier;
import com.auction.bid.domain.VersionCollisionException;
import com.auction.bid.observability.BidLogger;
import com.auction.bid.observability.BidMetrics;
import com.auction.bid.pipeline.BidMessage;
import com.auction.bid.pipeline.KafkaBidPipeline;
import com.auction.bid.realtime.LeaderBroadcaster;
import com.auction.bid.realtime.ResultNotifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Único consumer por partição (Princípio I). Ack manual só depois do commit da transação.
 * Colisão de versão: até 3 reprocessamentos com estado relido; esgotado, não dá ack e
 * relança para o DefaultErrorHandler reentregar o mesmo offset.
 */
@Component
public class BidListener {

    static final int MAX_COLLISION_RETRIES = 3;

    private static final Logger LOG = LoggerFactory.getLogger(BidListener.class);

    private final BidTransactionService transactions;
    private final ResultNotifier notifier;
    private final LeaderBroadcaster broadcaster;
    private final BidLogger bidLogger;
    private final BidMetrics metrics;

    public BidListener(BidTransactionService transactions, ResultNotifier notifier,
                       LeaderBroadcaster broadcaster, BidLogger bidLogger, BidMetrics metrics) {
        this.transactions = transactions;
        this.notifier = notifier;
        this.broadcaster = broadcaster;
        this.bidLogger = bidLogger;
        this.metrics = metrics;
    }

    @KafkaListener(id = "bid-listener", topics = "${auction.kafka.topic:bids}", groupId = "bid-processors",
            containerFactory = "bidListenerContainerFactory")
    public void onBid(ConsumerRecord<String, BidMessage> record, Acknowledgment ack) {
        long inicio = System.nanoTime();
        BidCommand cmd = record.value().toCommand();
        RiskTier tier = riskTier(record);
        // LogAppendTime do broker (Princípio II): nunca o timestamp do cliente
        Instant logAppendTime = Instant.ofEpochMilli(record.timestamp());

        BidOutcome outcome;
        try {
            outcome = processWithRetry(cmd, record.partition(), record.offset(), logAppendTime);
        } catch (RuntimeException e) {
            bidLogger.failure(String.valueOf(cmd.auctionId()), String.valueOf(cmd.bidId()),
                    cmd.userId(), e);
            throw e;
        }

        ack.acknowledge();
        afterCommit(cmd, outcome, tier, Duration.ofNanos(System.nanoTime() - inicio));
    }

    private BidOutcome processWithRetry(BidCommand cmd, int partition, long offset, Instant logAppendTime) {
        int reprocessamentos = 0;
        boolean duplicataRelida = false;
        while (true) {
            try {
                return transactions.process(cmd, partition, offset, logAppendTime);
            } catch (VersionCollisionException e) {
                reprocessamentos++;
                bidLogger.versionCollision(String.valueOf(cmd.auctionId()), String.valueOf(cmd.bidId()),
                        reprocessamentos);
                if (reprocessamentos > MAX_COLLISION_RETRIES) {
                    throw e;
                }
            } catch (DataIntegrityViolationException e) {
                // INSERT concorrente do mesmo bid_id: a linha já existe; a nova leitura devolve duplicata
                if (duplicataRelida) {
                    throw e;
                }
                duplicataRelida = true;
            }
        }
    }

    private void afterCommit(BidCommand cmd, BidOutcome outcome, RiskTier tier, Duration latencia) {
        String tierValue = tier.value();
        try {
            if (outcome.result().duplicate()) {
                bidLogger.duplicate(outcome.result(), cmd.userId(), tierValue);
            } else {
                metrics.decided(outcome.result().decision(), outcome.result().reason());
                metrics.latency(latencia);
                bidLogger.decision(outcome.result(), cmd.userId(), tierValue, latencia.toMillis());
            }
            notifier.notifyResult(cmd.userId(), outcome.result());
            outcome.leaderChange().ifPresent(broadcaster::broadcast);
        } catch (RuntimeException e) {
            // melhor esforço (contracts/websocket.md): falha de envio não reverte o lance
            LOG.warn("falha ao notificar lance {} (já commitado)", cmd.bidId(), e);
        }
    }

    private static RiskTier riskTier(ConsumerRecord<String, BidMessage> record) {
        Header header = record.headers().lastHeader(KafkaBidPipeline.HEADER_RISK_TIER);
        if (header == null) {
            return RiskTier.NORMAL;
        }
        return RiskTier.fromValue(new String(header.value(), StandardCharsets.UTF_8));
    }
}
