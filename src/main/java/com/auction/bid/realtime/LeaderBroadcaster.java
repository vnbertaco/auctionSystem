package com.auction.bid.realtime;

import com.auction.bid.domain.LeaderChange;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** Novo líder em /topic/auctions/{auction_id} (FR-006). Só após commit de lance aceito. */
@Component
public class LeaderBroadcaster {

    private final SimpMessagingTemplate messaging;

    public LeaderBroadcaster(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public void broadcast(LeaderChange change) {
        messaging.convertAndSend("/topic/auctions/" + change.auctionId(), change);
    }
}
