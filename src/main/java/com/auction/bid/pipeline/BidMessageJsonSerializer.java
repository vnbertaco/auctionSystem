package com.auction.bid.pipeline;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Serializer do valor do tópico bids. Instant sai como ISO-8601 ("2026-11-03T12:00:00Z"),
 * conforme contracts/kafka-message.md, e não como timestamp numérico (padrão do Jackson 2).
 * Referenciado em application.yml (spring.kafka.producer.value-serializer).
 */
public class BidMessageJsonSerializer extends JsonSerializer<BidMessage> {

    public BidMessageJsonSerializer() {
        super(JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build());
    }
}
