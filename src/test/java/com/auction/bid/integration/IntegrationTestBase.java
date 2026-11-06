package com.auction.bid.integration;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.lang.reflect.Type;

/**
 * Base dos testes de integração: PostgreSQL e Kafka reais (Testcontainers). Os containers
 * sobem uma vez por JVM. O tópico nasce com 6 partições e LogAppendTime (contracts/kafka-message.md).
 * Filtros de borda desligados; o usuário vem de X-User-Id via TestUserFilter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "auction.gateway.enabled=false")
@Import(TestUserFilter.Config.class)
public abstract class IntegrationTestBase {

    protected static final String TOPIC = "bids";

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17");
    // imagem suportada pelo Testcontainers (o apache/kafka não sobe com o KafkaContainer dele)
    private static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    static {
        POSTGRES.start();
        KAFKA.start();
        criarTopico();
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registro.add("spring.datasource.username", POSTGRES::getUsername);
        registro.add("spring.datasource.password", POSTGRES::getPassword);
        registro.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    private static void criarTopico() {
        Map<String, Object> config = Map.of("bootstrap.servers", KAFKA.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(config)) {
            NewTopic topico = new NewTopic(TOPIC, 6, (short) 1)
                    .configs(Map.of("message.timestamp.type", "LogAppendTime"));
            admin.createTopics(List.of(topico)).all().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("falha ao criar tópico de teste", e);
        }
    }

    @LocalServerPort
    protected int porta;

    @Autowired
    protected JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    protected UUID criarLeilao(String sellerId, String startingPrice, String riskTier,
                               Instant endTime, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auctions (auction_id, item_name, seller_id, risk_tier, starting_price,
                                      end_time, highest_bid, highest_user, version, status)
                VALUES (?, 'Item de teste', ?, ?, ?, ?, NULL, NULL, 0, ?)
                """, id, sellerId, riskTier, new BigDecimal(startingPrice),
                java.sql.Timestamp.from(endTime), status);
        return id;
    }

    protected UUID criarLeilao(String startingPrice) {
        return criarLeilao("s1", startingPrice, "hot", Instant.now().plusSeconds(86_400), "ativo");
    }

    /** POST /bids com o usuário em X-User-Id. Retorna o status HTTP. */
    protected int enviarLance(String userId, UUID auctionId, String valor, UUID bidId) {
        String corpo = """
                {"auction_id":"%s","valor":%s,"bid_id":"%s"}
                """.formatted(auctionId, valor, bidId);
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/bids"))
                .header("Content-Type", "application/json")
                .header(TestUserFilter.HEADER, userId)
                .POST(HttpRequest.BodyPublishers.ofString(corpo))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode();
        } catch (Exception e) {
            throw new IllegalStateException("falha no POST /bids", e);
        }
    }

    protected List<Map<String, Object>> linhasDoLance(UUID bidId) {
        return jdbc.queryForList("SELECT decision, reason, sequence, partition FROM bids WHERE bid_id = ?", bidId);
    }

    protected Map<String, Object> leilao(UUID auctionId) {
        return jdbc.queryForMap(
                "SELECT highest_bid, highest_user, version, status FROM auctions WHERE auction_id = ?", auctionId);
    }

    protected long contarLances(UUID auctionId) {
        return jdbc.queryForObject("SELECT count(*) FROM bids WHERE auction_id = ?", Long.class, auctionId);
    }

    /** Assina /user/queue/bid-results do usuário; devolve a fila de payloads JSON. */
    protected BlockingQueue<JsonNode> resultadosDe(String userId) throws Exception {
        BlockingQueue<JsonNode> fila = new LinkedBlockingQueue<>();
        StompSession sessao = conectar(userId);
        sessao.subscribe("/user/queue/bid-results", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                fila.add((JsonNode) payload);
            }
        });
        return fila;
    }

    /** Assina /topic/auctions/{id}; devolve a fila de broadcasts. */
    protected BlockingQueue<JsonNode> broadcastsDo(UUID auctionId) throws Exception {
        BlockingQueue<JsonNode> fila = new LinkedBlockingQueue<>();
        StompSession sessao = conectar("observador");
        sessao.subscribe("/topic/auctions/" + auctionId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                fila.add((JsonNode) payload);
            }
        });
        return fila;
    }

    private StompSession conectar(String userId) throws Exception {
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new JacksonJsonMessageConverter());
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.add(TestUserFilter.HEADER, userId);
        CompletableFuture<StompSession> futura = stomp.connectAsync(
                "ws://localhost:" + porta + "/ws", handshake, new StompHeaders(),
                new StompSessionHandlerAdapter() {
                });
        return futura.get(10, TimeUnit.SECONDS);
    }
}
