package com.auction.bid.realtime;

import com.auction.bid.domain.BidResult;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** Resultado final ao autor em /user/queue/bid-results (FR-006a). */
@Component
public class ResultNotifier {

    static final String DESTINATION = "/queue/bid-results";

    private final SimpMessagingTemplate messaging;

    public ResultNotifier(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public void notifyResult(String userId, BidResult result) {
        messaging.convertAndSendToUser(userId, DESTINATION, result);
    }
}
