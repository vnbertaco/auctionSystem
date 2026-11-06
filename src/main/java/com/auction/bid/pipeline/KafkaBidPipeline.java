package com.auction.bid.pipeline;

import com.auction.bid.domain.BidCommand;
import com.auction.bid.domain.RiskTier;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** key = auction_id: mesmo leilão sempre na mesma partição (Princípio I). */
@Component
public class KafkaBidPipeline implements BidPipeline {

    public static final String HEADER_BID_ID = "bid_id";
    public static final String HEADER_RISK_TIER = "risk_tier";

    private final KafkaTemplate<String, BidMessage> kafkaTemplate;
    private final String topic;
    private final long publishTimeoutSeconds;

    public KafkaBidPipeline(KafkaTemplate<String, BidMessage> kafkaTemplate,
                            @Value("${auction.kafka.topic:bids}") String topic,
                            @Value("${auction.kafka.publish-timeout-seconds:5}") long publishTimeoutSeconds) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.publishTimeoutSeconds = publishTimeoutSeconds;
    }

    @Override
    public void submit(BidCommand command, RiskTier riskTier) {
        ProducerRecord<String, BidMessage> record =
                new ProducerRecord<>(topic, command.auctionId().toString(), BidMessage.from(command));
        record.headers()
                .add(HEADER_BID_ID, command.bidId().toString().getBytes(StandardCharsets.UTF_8))
                .add(HEADER_RISK_TIER, riskTier.value().getBytes(StandardCharsets.UTF_8));
        try {
            kafkaTemplate.send(record).get(publishTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BidPublishException("publicação interrompida", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new BidPublishException("falha ao publicar lance no Kafka", e);
        }
    }
}
