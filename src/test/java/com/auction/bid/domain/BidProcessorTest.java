package com.auction.bid.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Casos derivados das regras de data-model.md (ordem 2 a 8), não do que o código faz. */
class BidProcessorTest {

    private static final Instant ABERTO_ATE = Instant.parse("2026-12-01T00:00:00Z");
    private static final Instant ANTES_DO_FIM = Instant.parse("2026-11-30T23:59:59Z");
    private static final String VENDEDOR = "s1";

    private static BidCommand lance(String userId, String valor) {
        return new BidCommand(UUID.randomUUID(), UUID.randomUUID(), userId, new BigDecimal(valor), null);
    }

    private static AuctionState leilao(String status, Instant fim, String inicial, String maior, String lider) {
        return new AuctionState(UUID.randomUUID(), VENDEDOR, RiskTier.HOT, new BigDecimal(inicial), fim,
                maior == null ? null : new BigDecimal(maior), lider, maior == null ? 0 : 3, status);
    }

    private static Optional<AuctionState> sem() {
        return Optional.empty();
    }

    @Nested
    class Leilao {

        @Test
        void inexistenteRejeitaComoAuctionNotFound() {
            BidDecisionOutcome r = BidProcessor.decide(lance("u1", "150"), sem(), ANTES_DO_FIM);

            assertThat(r.decision()).isEqualTo(Decision.REJEITADO);
            assertThat(r.reason()).isEqualTo(Reason.AUCTION_NOT_FOUND);
        }

        @Test
        void encerradoPorStatusRejeitaComoAuctionClosed() {
            BidDecisionOutcome r = BidProcessor.decide(lance("u1", "150"),
                    Optional.of(leilao("encerrado", ABERTO_ATE, "100", null, null)), ANTES_DO_FIM);

            assertThat(r.reason()).isEqualTo(Reason.AUCTION_CLOSED);
        }

        @Test
        void logAppendTimeIgualAoFimEhFechado() {
            BidDecisionOutcome r = BidProcessor.decide(lance("u1", "150"),
                    Optional.of(leilao("ativo", ABERTO_ATE, "100", null, null)), ABERTO_ATE);

            assertThat(r.reason()).isEqualTo(Reason.AUCTION_CLOSED);
        }

        @Test
        void logAppendTimeAntesDoFimEhAceito() {
            BidDecisionOutcome r = BidProcessor.decide(lance("u1", "150"),
                    Optional.of(leilao("ativo", ABERTO_ATE, "100", null, null)), ABERTO_ATE.minusMillis(1));

            assertThat(r.isAccepted()).isTrue();
        }
    }

    @Nested
    class Participantes {

        @Test
        void vendedorNaoPodeDarLance() {
            BidDecisionOutcome r = BidProcessor.decide(lance(VENDEDOR, "150"),
                    Optional.of(leilao("ativo", ABERTO_ATE, "100", null, null)), ANTES_DO_FIM);

            assertThat(r.reason()).isEqualTo(Reason.SELLER_CANNOT_BID);
        }

        @Test
        void liderAtualNaoPodeCobrirASiMesmo() {
            BidDecisionOutcome r = BidProcessor.decide(lance("u1", "200"),
                    Optional.of(leilao("ativo", ABERTO_ATE, "100", "150", "u1")), ANTES_DO_FIM);

            assertThat(r.reason()).isEqualTo(Reason.ALREADY_LEADING);
        }

        @Test
        void vendedorTemPrecedenciaSobreLider() {
            // seller_id == highest_user: a regra do vendedor (passo 4) vem antes da do líder (passo 5)
            BidDecisionOutcome r = BidProcessor.decide(lance(VENDEDOR, "200"),
                    Optional.of(leilao("ativo", ABERTO_ATE, "100", "150", VENDEDOR)), ANTES_DO_FIM);

            assertThat(r.reason()).isEqualTo(Reason.SELLER_CANNOT_BID);
        }
    }

    @Nested
    class Valores {

        @Test
        void primeiroLanceIgualAoStartingPriceEhAceito() {
            BidDecisionOutcome r = BidProcessor.decide(lance("u1", "100.00"),
                    Optional.of(leilao("ativo", ABERTO_ATE, "100", null, null)), ANTES_DO_FIM);

            assertThat(r.isAccepted()).isTrue();
        }

        @Test
        void primeiroLanceAbaixoDoStartingPriceRejeita() {
            BidDecisionOutcome r = BidProcessor.decide(lance("u1", "99.99"),
                    Optional.of(leilao("ativo", ABERTO_ATE, "100", null, null)), ANTES_DO_FIM);

            assertThat(r.reason()).isEqualTo(Reason.BELOW_STARTING_PRICE);
        }

        @Test
        void lanceIgualAoMaiorAtualRejeita() {
            BidDecisionOutcome r = BidProcessor.decide(lance("u2", "150.00"),
                    Optional.of(leilao("ativo", ABERTO_ATE, "100", "150", "u1")), ANTES_DO_FIM);

            assertThat(r.reason()).isEqualTo(Reason.BELOW_CURRENT_BID);
        }

        @Test
        void lanceMenorQueMaiorAtualRejeita() {
            BidDecisionOutcome r = BidProcessor.decide(lance("u2", "100"),
                    Optional.of(leilao("ativo", ABERTO_ATE, "100", "150", "u1")), ANTES_DO_FIM);

            assertThat(r.reason()).isEqualTo(Reason.BELOW_CURRENT_BID);
        }

        @Test
        void lanceValidoAcimaDoMaiorAtualEhAceito() {
            BidDecisionOutcome r = BidProcessor.decide(lance("u2", "150.01"),
                    Optional.of(leilao("ativo", ABERTO_ATE, "100", "150", "u1")), ANTES_DO_FIM);

            assertThat(r).isEqualTo(BidDecisionOutcome.accepted());
            assertThat(r.reason()).isNull();
        }
    }

    @Test
    void ordemAuctionClosedAntesDeSellerCannotBid() {
        // leilão encerrado e lance do vendedor: o fechamento (passo 3) decide
        BidDecisionOutcome r = BidProcessor.decide(lance(VENDEDOR, "150"),
                Optional.of(leilao("encerrado", ABERTO_ATE, "100", null, null)), ANTES_DO_FIM);

        assertThat(r.reason()).isEqualTo(Reason.AUCTION_CLOSED);
    }
}
