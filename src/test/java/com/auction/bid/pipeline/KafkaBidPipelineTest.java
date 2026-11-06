package com.auction.bid.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.auction.bid.domain.BidCommand;
import com.auction.bid.domain.RiskTier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import java.util.UUID;

class KafkaBidPipelineTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, BidMessage> template = mock(KafkaTemplate.class);

    private KafkaBidPipeline pipeline() {
        return new KafkaBidPipeline(template, "bids", 1);
    }

    private static BidCommand comando() {
        return new BidCommand(UUID.fromString("00000000-0000-4000-8000-000000000001"),
                UUID.fromString("00000000-0000-4000-8000-0000000000aa"),
                "u1", new BigDecimal("150.00"), null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publicaComKeyAuctionIdEHeadersBidIdERiskTier() {
        when(template.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        pipeline().submit(comando(), RiskTier.HOT);

        ArgumentCaptor<ProducerRecord<String, BidMessage>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        org.mockito.Mockito.verify(template).send(captor.capture());
        ProducerRecord<String, BidMessage> enviado = captor.getValue();

        assertThat(enviado.topic()).isEqualTo("bids");
        assertThat(enviado.key()).isEqualTo("00000000-0000-4000-8000-0000000000aa");
        assertThat(new String(enviado.headers().lastHeader("bid_id").value(), StandardCharsets.UTF_8))
                .isEqualTo("00000000-0000-4000-8000-000000000001");
        assertThat(new String(enviado.headers().lastHeader("risk_tier").value(), StandardCharsets.UTF_8))
                .isEqualTo("hot");
        assertThat(enviado.value().userId()).isEqualTo("u1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void falhaDePublicacaoLancaBidPublishException() {
        CompletableFuture<SendResult<String, BidMessage>> falha = new CompletableFuture<>();
        falha.completeExceptionally(new RuntimeException("broker fora"));
        when(template.send(any(ProducerRecord.class))).thenReturn(falha);

        assertThatThrownBy(() -> pipeline().submit(comando(), RiskTier.NORMAL))
                .isInstanceOf(BidPublishException.class);
    }
}
