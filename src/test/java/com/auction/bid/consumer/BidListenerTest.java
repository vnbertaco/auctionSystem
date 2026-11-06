package com.auction.bid.consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.auction.bid.domain.BidCommand;
import com.auction.bid.domain.BidOutcome;
import com.auction.bid.domain.BidResult;
import com.auction.bid.domain.BidTransactionService;
import com.auction.bid.domain.Decision;
import com.auction.bid.domain.LeaderChange;
import com.auction.bid.domain.Reason;
import com.auction.bid.domain.VersionCollisionException;
import com.auction.bid.observability.BidLogger;
import com.auction.bid.observability.BidMetrics;
import com.auction.bid.pipeline.BidMessage;
import com.auction.bid.realtime.LeaderBroadcaster;
import com.auction.bid.realtime.ResultNotifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.Acknowledgment;
import org.mockito.InOrder;

class BidListenerTest {

    private final UUID bidId = UUID.randomUUID();
    private final UUID auctionId = UUID.randomUUID();
    private BidTransactionService transactions;
    private ResultNotifier notifier;
    private LeaderBroadcaster broadcaster;
    private BidListener listener;
    private Acknowledgment ack;

    @BeforeEach
    void setUp() {
        transactions = mock(BidTransactionService.class);
        notifier = mock(ResultNotifier.class);
        broadcaster = mock(LeaderBroadcaster.class);
        ack = mock(Acknowledgment.class);
        listener = new BidListener(transactions, notifier, broadcaster, new BidLogger(),
                new BidMetrics(new SimpleMeterRegistry()));
    }

    private ConsumerRecord<String, BidMessage> registro(String tier) {
        BidMessage msg = new BidMessage(bidId, auctionId, "u1", new BigDecimal("150.00"), null);
        ConsumerRecord<String, BidMessage> rec =
                new ConsumerRecord<>("bids", 2, 42L, Instant.parse("2026-11-04T10:00:00Z").toEpochMilli(),
                        null, 0, 0, auctionId.toString(), msg, new RecordHeaders(), Optional.empty());
        rec.headers().add("risk_tier", tier.getBytes(StandardCharsets.UTF_8));
        return rec;
    }

    private BidResult aceito() {
        return new BidResult(bidId, auctionId, Decision.ACEITO, null, 42L, false);
    }

    @Test
    void ackSoDepoisDoCommitENotificaAposAck() {
        BidOutcome outcome = new BidOutcome(aceito(),
                Optional.of(new LeaderChange(auctionId, new BigDecimal("150.00"), "u1", 6L)));
        when(transactions.process(any(BidCommand.class), eq(2), eq(42L), any(Instant.class)))
                .thenReturn(outcome);

        listener.onBid(registro("hot"), ack);

        InOrder ordem = inOrder(transactions, ack, notifier, broadcaster);
        ordem.verify(transactions).process(any(BidCommand.class), eq(2), eq(42L), any(Instant.class));
        ordem.verify(ack).acknowledge();
        ordem.verify(notifier).notifyResult("u1", aceito());
        ordem.verify(broadcaster).broadcast(any(LeaderChange.class));
    }

    @Test
    void logAppendTimeVemDoRegistroNaoDoCliente() {
        when(transactions.process(any(BidCommand.class), anyInt(), anyLong(), any(Instant.class)))
                .thenReturn(new BidOutcome(aceito(), Optional.empty()));

        listener.onBid(registro("normal"), ack);

        verify(transactions).process(any(BidCommand.class), eq(2), eq(42L),
                eq(Instant.parse("2026-11-04T10:00:00Z")));
    }

    @Test
    void duplicataReenviaResultadoOriginalSemBroadcast() {
        BidResult dup = aceito().asDuplicate();
        when(transactions.process(any(BidCommand.class), anyInt(), anyLong(), any(Instant.class)))
                .thenReturn(new BidOutcome(dup, Optional.empty()));

        listener.onBid(registro("hot"), ack);

        verify(notifier).notifyResult("u1", dup);
        verify(broadcaster, never()).broadcast(any());
    }

    @Test
    void rejeitadoNaoFazBroadcast() {
        BidResult rejeitado = new BidResult(bidId, auctionId, Decision.REJEITADO,
                Reason.BELOW_CURRENT_BID, 42L, false);
        when(transactions.process(any(BidCommand.class), anyInt(), anyLong(), any(Instant.class)))
                .thenReturn(new BidOutcome(rejeitado, Optional.empty()));

        listener.onBid(registro("hot"), ack);

        verify(notifier).notifyResult("u1", rejeitado);
        verify(broadcaster, never()).broadcast(any());
    }

    @Test
    void colisaoDeVersaoRepetiAteTresVezesEDepoisDaAck() {
        when(transactions.process(any(BidCommand.class), anyInt(), anyLong(), any(Instant.class)))
                .thenThrow(new VersionCollisionException(auctionId, 5L))
                .thenThrow(new VersionCollisionException(auctionId, 5L))
                .thenReturn(new BidOutcome(aceito(), Optional.empty()));

        listener.onBid(registro("hot"), ack);

        verify(transactions, times(3)).process(any(BidCommand.class), anyInt(), anyLong(), any(Instant.class));
        verify(ack).acknowledge();
    }

    @Test
    void colisaoPersistenteNaoDaAckERelancaParaReentrega() {
        when(transactions.process(any(BidCommand.class), anyInt(), anyLong(), any(Instant.class)))
                .thenThrow(new VersionCollisionException(auctionId, 5L));

        assertThatThrownBy(() -> listener.onBid(registro("hot"), ack))
                .isInstanceOf(VersionCollisionException.class);

        // 1 tentativa + 3 reprocessamentos
        verify(transactions, times(BidListener.MAX_COLLISION_RETRIES + 1))
                .process(any(BidCommand.class), anyInt(), anyLong(), any(Instant.class));
        verify(ack, never()).acknowledge();
    }

    @Test
    void duplicataConcorrenteRelêUmaVezEDevolveDuplicata() {
        when(transactions.process(any(BidCommand.class), anyInt(), anyLong(), any(Instant.class)))
                .thenThrow(new DataIntegrityViolationException("pk"))
                .thenReturn(new BidOutcome(aceito().asDuplicate(), Optional.empty()));

        listener.onBid(registro("hot"), ack);

        verify(notifier).notifyResult(eq("u1"), any(BidResult.class));
        verify(ack).acknowledge();
    }

    @Test
    void falhaDeNotificacaoNaoDesfazAck() {
        when(transactions.process(any(BidCommand.class), anyInt(), anyLong(), any(Instant.class)))
                .thenReturn(new BidOutcome(aceito(), Optional.empty()));
        org.mockito.Mockito.doThrow(new RuntimeException("ws fora")).when(notifier)
                .notifyResult(any(), any());

        listener.onBid(registro("hot"), ack);

        verify(ack).acknowledge();
    }

    @Test
    void headerRiskTierAusenteUsaNormal() {
        when(transactions.process(any(BidCommand.class), anyInt(), anyLong(), any(Instant.class)))
                .thenReturn(new BidOutcome(aceito(), Optional.empty()));
        ConsumerRecord<String, BidMessage> semHeader = registro("hot");
        semHeader.headers().remove("risk_tier");

        listener.onBid(semHeader, ack);

        verify(ack).acknowledge();
    }

    @Test
    void erroInesperadoPropagaSemAck() {
        when(transactions.process(any(BidCommand.class), anyInt(), anyLong(), any(Instant.class)))
                .thenThrow(new IllegalStateException("bug"));

        assertThatThrownBy(() -> listener.onBid(registro("hot"), ack))
                .isInstanceOf(IllegalStateException.class);
        verify(ack, never()).acknowledge();
    }
}
