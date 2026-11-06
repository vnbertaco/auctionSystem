package com.auction.bid.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.auction.bid.domain.BidCommand;
import com.auction.bid.domain.RiskTier;
import com.auction.bid.observability.BidMetrics;
import com.auction.bid.pipeline.BidPipeline;
import com.auction.bid.pipeline.BidPublishException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BidController.class)
class BidControllerTest {

    private static final String BID_ID = "00000000-0000-4000-8000-000000000001";
    private static final String AUCTION_ID = "00000000-0000-4000-8000-0000000000aa";

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private RiskTierResolver riskTiers;
    @MockitoBean
    private BidPipeline pipeline;
    @MockitoBean
    private BidMetrics metrics;

    private static String corpo(String bidId, String auctionId, String valor) {
        return """
                {"auction_id":"%s","valor":%s,"bid_id":"%s"}
                """.formatted(auctionId, valor, bidId);
    }

    @Test
    void aceitaComStatus202ERetornaBidIdERecebido() throws Exception {
        when(riskTiers.resolve(any())).thenReturn(RiskTier.HOT);

        mvc.perform(post("/bids").requestAttr("userId", "u1")
                        .contentType("application/json").content(corpo(BID_ID, AUCTION_ID, "150.00")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.bid_id").value(BID_ID))
                .andExpect(jsonPath("$.status").value("RECEIVED"));

        verify(pipeline).submit(any(BidCommand.class), any(RiskTier.class));
    }

    @Test
    void userIdVemDoAtributoNuncaDoCorpo() throws Exception {
        when(riskTiers.resolve(any())).thenReturn(RiskTier.NORMAL);
        String corpoComUsuarioFalso = """
                {"auction_id":"%s","valor":150,"bid_id":"%s","user_id":"hacker"}
                """.formatted(AUCTION_ID, BID_ID);

        mvc.perform(post("/bids").requestAttr("userId", "u1")
                        .contentType("application/json").content(corpoComUsuarioFalso))
                .andExpect(status().isAccepted());

        verify(pipeline).submit(org.mockito.ArgumentMatchers.argThat(c -> "u1".equals(c.userId())),
                any(RiskTier.class));
    }

    @Test
    void timestampClienteIso8601EhAceitoEChegaAoComando() throws Exception {
        when(riskTiers.resolve(any())).thenReturn(RiskTier.NORMAL);
        String comTimestamp = """
                {"auction_id":"%s","valor":150.00,"bid_id":"%s","timestamp_cliente":"2026-11-03T12:00:00Z"}
                """.formatted(AUCTION_ID, BID_ID);

        mvc.perform(post("/bids").requestAttr("userId", "u1")
                        .contentType("application/json").content(comTimestamp))
                .andExpect(status().isAccepted());

        verify(pipeline).submit(org.mockito.ArgumentMatchers.argThat(c ->
                java.time.Instant.parse("2026-11-03T12:00:00Z").equals(c.timestampCliente())),
                any(RiskTier.class));
    }

    @Test
    void timestampClienteEmFormatoInvalidoRetorna400() throws Exception {
        String invalido = """
                {"auction_id":"%s","valor":150.00,"bid_id":"%s","timestamp_cliente":"03/11/2026 12:00"}
                """.formatted(AUCTION_ID, BID_ID);

        mvc.perform(post("/bids").requestAttr("userId", "u1")
                        .contentType("application/json").content(invalido))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bidIdNaoUuidRetorna400() throws Exception {
        mvc.perform(post("/bids").requestAttr("userId", "u1")
                        .contentType("application/json").content(corpo("nao-uuid", AUCTION_ID, "150")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void valorZeroOuNegativoRetorna400() throws Exception {
        mvc.perform(post("/bids").requestAttr("userId", "u1")
                        .contentType("application/json").content(corpo(BID_ID, AUCTION_ID, "0")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/bids").requestAttr("userId", "u1")
                        .contentType("application/json").content(corpo(BID_ID, AUCTION_ID, "-5")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void campoAusenteRetorna400() throws Exception {
        mvc.perform(post("/bids").requestAttr("userId", "u1")
                        .contentType("application/json")
                        .content("{\"auction_id\":\"" + AUCTION_ID + "\",\"valor\":150}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void falhaDePublicacaoRetorna503() throws Exception {
        when(riskTiers.resolve(any())).thenReturn(RiskTier.NORMAL);
        org.mockito.Mockito.doThrow(new BidPublishException("kafka", null))
                .when(pipeline).submit(any(), any());

        mvc.perform(post("/bids").requestAttr("userId", "u1")
                        .contentType("application/json").content(corpo(BID_ID, AUCTION_ID, "150")))
                .andExpect(status().isServiceUnavailable());
    }
}
