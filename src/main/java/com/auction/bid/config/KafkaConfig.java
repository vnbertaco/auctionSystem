package com.auction.bid.config;

import com.auction.bid.pipeline.BidMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Container do listener: ack manual, concorrência = nº de partições (uma thread por
 * partição, cada uma sequencial) e reentrega com backoff fixo de 1 s (at-least-once).
 * Producer/consumer (JSON, acks=all, idempotência) vêm do application.yml.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BidMessage> bidListenerContainerFactory(
            ConsumerFactory<String, BidMessage> consumerFactory,
            @Value("${auction.kafka.concurrency:6}") int concurrency) {
        ConcurrentKafkaListenerContainerFactory<String, BidMessage> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                new FixedBackOff(1_000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }
}
