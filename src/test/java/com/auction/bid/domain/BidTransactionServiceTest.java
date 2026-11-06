package com.auction.bid.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.auction.bid.observability.BidMetrics;
import com.auction.bid.persistence.AuctionEntity;
import com.auction.bid.persistence.AuctionRepository;
import com.auction.bid.persistence.BidEntity;
import com.auction.bid.persistence.BidRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class BidTransactionServiceTest {

    private static final Instant LOG_TIME = Instant.parse("2026-11-04T10:00:00Z");
    private static final Instant FIM = Instant.parse("2026-12-01T00:00:00Z");

    @Mock
    private AuctionRepository auctions;
    @Mock
    private BidRepository bids;

    private BidTransactionService service;
    private UUID auctionId;
    private UUID bidId;

    @BeforeEach
    void setUp() {
        service = new BidTransactionService(auctions, bids, new BidMetrics(new SimpleMeterRegistry()));
        auctionId = UUID.randomUUID();
        bidId = UUID.randomUUID();
    }

    private BidCommand comando(String userId, String valor) {
        return new BidCommand(bidId, auctionId, userId, new BigDecimal(valor), null);
    }

    private void leilaoComEstado(AuctionState estado) {
        AuctionEntity entidade = org.mockito.Mockito.mock(AuctionEntity.class);
        when(entidade.toState()).thenReturn(estado);
        when(auctions.findById(auctionId)).thenReturn(Optional.of(entidade));
    }

    private AuctionState estado(String maior, String lider, long versao) {
        return new AuctionState(auctionId, "s1", RiskTier.NORMAL, new BigDecimal("100"), FIM,
                maior == null ? null : new BigDecimal(maior), lider, versao, "ativo");
    }

    @Test
    void bidIdExistenteDevolveResultadoOriginalComoDuplicataSemEscrever() {
        BidEntity original = BidEntity.create(comando("u1", "150"),
                BidDecisionOutcome.accepted(), 2, 42L, LOG_TIME, LOG_TIME);
        when(bids.findById(bidId)).thenReturn(Optional.of(original));

        BidOutcome outcome = service.process(comando("u1", "150"), 2, 99L, LOG_TIME);

        assertThat(outcome.result().duplicate()).isTrue();
        assertThat(outcome.result().decision()).isEqualTo(Decision.ACEITO);
        assertThat(outcome.result().sequence()).isEqualTo(42L);
        assertThat(outcome.leaderChange()).isEmpty();
        verify(auctions, never()).updateLeader(any(), any(), anyString(), anyLong());
        verify(bids, never()).saveAndFlush(any());
    }

    @Test
    void violacaoDePkNoInsertPropagaParaQueOListenerRelea() {
        when(bids.findById(bidId)).thenReturn(Optional.empty());
        leilaoComEstado(estado(null, null, 0));
        when(bids.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("pk duplicada"));

        assertThatThrownBy(() -> service.process(comando("u1", "150"), 0, 1L, LOG_TIME))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(auctions, never()).updateLeader(any(), any(), anyString(), anyLong());
    }

    @Test
    void updateLeaderComZeroLinhasLancaColisao() {
        when(bids.findById(bidId)).thenReturn(Optional.empty());
        leilaoComEstado(estado("120", "u9", 5));
        when(auctions.updateLeader(eq(auctionId), any(), anyString(), eq(5L))).thenReturn(0);

        assertThatThrownBy(() -> service.process(comando("u1", "150"), 0, 1L, LOG_TIME))
                .isInstanceOf(VersionCollisionException.class);
    }

    @Test
    void aceitoAtualizaLiderComVersaoEsperadaEDevolveMudanca() {
        when(bids.findById(bidId)).thenReturn(Optional.empty());
        leilaoComEstado(estado("120", "u9", 5));
        when(auctions.updateLeader(auctionId, new BigDecimal("150"), "u1", 5L)).thenReturn(1);

        BidOutcome outcome = service.process(comando("u1", "150"), 0, 7L, LOG_TIME);

        assertThat(outcome.result().decision()).isEqualTo(Decision.ACEITO);
        assertThat(outcome.result().sequence()).isEqualTo(7L);
        assertThat(outcome.leaderChange()).hasValueSatisfying(l -> {
            assertThat(l.version()).isEqualTo(6L);
            assertThat(l.highestUser()).isEqualTo("u1");
        });
    }

    @Test
    void rejeitadoGravaLinhaSemTocarNoLeilao() {
        when(bids.findById(bidId)).thenReturn(Optional.empty());
        leilaoComEstado(estado("150", "u9", 5));

        BidOutcome outcome = service.process(comando("u1", "100"), 0, 3L, LOG_TIME);

        assertThat(outcome.result().reason()).isEqualTo(Reason.BELOW_CURRENT_BID);
        assertThat(outcome.leaderChange()).isEmpty();
        verify(bids).saveAndFlush(any());
        verify(auctions, never()).updateLeader(any(), any(), anyString(), anyLong());
    }

    @Test
    void auctionNotFoundNaoGravaLinhaENaoTemSequence() {
        when(bids.findById(bidId)).thenReturn(Optional.empty());
        when(auctions.findById(auctionId)).thenReturn(Optional.empty());

        BidOutcome outcome = service.process(comando("u1", "150"), 0, 3L, LOG_TIME);

        assertThat(outcome.result().reason()).isEqualTo(Reason.AUCTION_NOT_FOUND);
        assertThat(outcome.result().sequence()).isNull();
        verify(bids, never()).saveAndFlush(any());
    }
}
