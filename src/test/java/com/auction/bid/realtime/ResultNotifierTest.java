package com.auction.bid.realtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import com.auction.bid.domain.Decision;
import com.auction.bid.domain.BidResult;
import com.auction.bid.domain.LeaderChange;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class ResultNotifierTest {

    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);

    @Test
    void resultadoVaiAoAutorEmFilaDeUsuario() {
        UUID auction = UUID.randomUUID();
        BidResult result = new BidResult(UUID.randomUUID(), auction, Decision.ACEITO, null, 1L, false);

        new ResultNotifier(messaging).notifyResult("u1", result);

        verify(messaging).convertAndSendToUser("u1", "/queue/bid-results", result);
    }

    @Test
    void broadcastDoLiderSoEmTopicoDoLeilao() {
        UUID auction = UUID.randomUUID();
        LeaderChange change = new LeaderChange(auction, new BigDecimal("150.00"), "u1", 6L);

        new LeaderBroadcaster(messaging).broadcast(change);

        verify(messaging).convertAndSend("/topic/auctions/" + auction, change);
    }

    @Test
    void naoUsaBroadcastParaResultadoDoAutor() {
        UUID auction = UUID.randomUUID();
        BidResult result = new BidResult(UUID.randomUUID(), auction, Decision.REJEITADO, null, 1L, false);

        new ResultNotifier(messaging).notifyResult("u1", result);

        verify(messaging, never()).convertAndSend(anyString(), any(Object.class));
    }
}
