package com.auction.bid.api;

import com.auction.bid.domain.BidCommand;
import com.auction.bid.domain.RiskTier;
import com.auction.bid.observability.BidMetrics;
import com.auction.bid.pipeline.BidPipeline;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bids")
public class BidController {

    private final RiskTierResolver riskTiers;
    private final BidPipeline pipeline;
    private final BidMetrics metrics;

    public BidController(RiskTierResolver riskTiers, BidPipeline pipeline, BidMetrics metrics) {
        this.riskTiers = riskTiers;
        this.pipeline = pipeline;
        this.metrics = metrics;
    }

    /** user_id vem do JWT (atributo de requisição), nunca do corpo. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BidReceived submit(@Valid @RequestBody BidRequest request,
                              @RequestAttribute("userId") String userId) {
        RiskTier tier = riskTiers.resolve(request.auctionId());
        BidCommand command = new BidCommand(request.bidId(), request.auctionId(), userId,
                request.valor().setScale(2), request.timestampCliente());
        pipeline.submit(command, tier);
        metrics.received();
        return new BidReceived(request.bidId(), "RECEIVED");
    }
}
