package com.auction.bid.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Garante que a mensagem publicada no Kafka serializa timestamp_cliente como string ISO-8601
 * (contracts/kafka-message.md), usando o mesmo serializer configurado no application.yml.
 */
class BidMessageSerializationTest {

    private final JsonSerializer<BidMessage> serializer = new BidMessageJsonSerializer();

    @Test
    void timestampClienteSaiComoIso8601() throws Exception {
        BidMessage mensagem = new BidMessage(
                UUID.fromString("3f9c1e2a-8b7d-4c6e-9a1f-2d3e4f5a6b7c"),
                UUID.fromString("6f1c2a3e-1111-4a6b-9c0d-000000000001"),
                "u1",
                new BigDecimal("150.00"),
                Instant.parse("2026-11-03T12:00:00Z"));

        JsonNode json = new ObjectMapper().readTree(serializer.serialize("bids", mensagem));

        assertThat(json.get("timestamp_cliente").isTextual()).isTrue();
        assertThat(json.get("timestamp_cliente").asText()).isEqualTo("2026-11-03T12:00:00Z");
    }

    @Test
    void timestampClienteIdaEVoltaSemPerda() throws Exception {
        Instant instante = Instant.parse("2026-11-03T12:00:00.123Z");
        BidMessage original = new BidMessage(UUID.randomUUID(), UUID.randomUUID(), "u1",
                new BigDecimal("150.00"), instante);

        JsonDeserializer<BidMessage> deserializer = new JsonDeserializer<>(BidMessage.class, false);
        BidMessage lida = deserializer.deserialize("bids", serializer.serialize("bids", original));

        assertThat(lida.timestampCliente()).isEqualTo(instante);
    }
}
