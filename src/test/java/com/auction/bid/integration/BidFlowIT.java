package com.auction.bid.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;

/** Fluxo ponta a ponta: HTTP → Kafka → consumer → Postgres → WebSocket (quickstart 1 a 4, 7 e 8). */
class BidFlowIT extends IntegrationTestBase {

    private static final Duration ESPERA = Duration.ofSeconds(20);

    private JsonNode proximo(BlockingQueue<JsonNode> fila) throws InterruptedException {
        JsonNode msg = fila.poll(15, TimeUnit.SECONDS);
        assertThat(msg).as("mensagem WebSocket").isNotNull();
        return msg;
    }

    private void aguardarLance(UUID bidId) {
        await().atMost(ESPERA).untilAsserted(() -> assertThat(linhasDoLance(bidId)).hasSize(1));
    }

    @Test
    void cenario1LanceMaiorQueOAtualEAceitoEAtualizaLider() throws Exception {
        UUID leilao = criarLeilao("100");
        BlockingQueue<JsonNode> resultados = resultadosDe("u1");
        BlockingQueue<JsonNode> broadcasts = broadcastsDo(leilao);
        UUID bid = UUID.randomUUID();

        assertThat(enviarLance("u1", leilao, "150.00", bid)).isEqualTo(202);

        aguardarLance(bid);
        assertThat(linhasDoLance(bid).get(0)).containsEntry("decision", "aceito");
        assertThat(leilao(leilao)).containsEntry("version", 1L);
        assertThat(leilao(leilao).get("highest_user")).isEqualTo("u1");

        JsonNode resultado = proximo(resultados);
        assertThat(resultado.get("decision").asString()).isEqualTo("aceito");
        assertThat(resultado.get("duplicate").asBoolean()).isFalse();

        JsonNode lider = proximo(broadcasts);
        assertThat(lider.get("highest_bid").decimalValue().compareTo(new java.math.BigDecimal("150"))).isZero();
        assertThat(lider.get("version").asLong()).isEqualTo(1L);
    }

    @Test
    void cenario2LanceAbaixoDoAtualEhRejeitadoSemAlterarEstado() throws Exception {
        UUID leilao = criarLeilao("100");
        UUID primeiro = UUID.randomUUID();
        enviarLance("u1", leilao, "150.00", primeiro);
        aguardarLance(primeiro);
        Map<String, Object> antes = leilao(leilao);

        BlockingQueue<JsonNode> resultados = resultadosDe("u2");
        UUID segundo = UUID.randomUUID();
        assertThat(enviarLance("u2", leilao, "100.00", segundo)).isEqualTo(202);
        aguardarLance(segundo);

        assertThat(linhasDoLance(segundo).get(0))
                .containsEntry("decision", "rejeitado")
                .containsEntry("reason", "BELOW_CURRENT_BID");
        assertThat(leilao(leilao)).isEqualTo(antes);

        JsonNode resultado = proximo(resultados);
        assertThat(resultado.get("decision").asString()).isEqualTo("rejeitado");
        assertThat(resultado.get("reason").asString()).isEqualTo("BELOW_CURRENT_BID");
    }

    @Test
    void regrasDeVendedorELiderSaoAplicadas() {
        UUID leilao = criarLeilao("100");
        UUID doVendedor = UUID.randomUUID();
        UUID doLider = UUID.randomUUID();
        UUID doLiderDeNovo = UUID.randomUUID();
        enviarLance("s1", leilao, "120.00", doVendedor);
        enviarLance("u1", leilao, "130.00", doLider);
        aguardarLance(doLider);
        enviarLance("u1", leilao, "140.00", doLiderDeNovo);

        await().atMost(ESPERA).untilAsserted(() -> {
            assertThat(linhasDoLance(doVendedor).get(0)).containsEntry("reason", "SELLER_CANNOT_BID");
            assertThat(linhasDoLance(doLiderDeNovo).get(0)).containsEntry("reason", "ALREADY_LEADING");
        });
    }

    @Test
    void lanceAposEncerramentoEhAuctionClosed() {
        UUID encerrado = criarLeilao("s1", "100", "hot", Instant.now().minusSeconds(60), "ativo");
        UUID bid = UUID.randomUUID();

        enviarLance("u1", encerrado, "150.00", bid);

        aguardarLance(bid);
        assertThat(linhasDoLance(bid).get(0)).containsEntry("reason", "AUCTION_CLOSED");
    }

    @Test
    void leilaoInexistenteNaoGeraLinhaEResultadoSemSequence() throws Exception {
        BlockingQueue<JsonNode> resultados = resultadosDe("u1");
        UUID inexistente = UUID.randomUUID();
        UUID bid = UUID.randomUUID();

        assertThat(enviarLance("u1", inexistente, "150.00", bid)).isEqualTo(202);

        JsonNode resultado = proximo(resultados);
        assertThat(resultado.get("reason").asString()).isEqualTo("AUCTION_NOT_FOUND");
        assertThat(resultado.get("sequence").isNull()).isTrue();
        // sem FK: nenhuma linha em bids para esse leilão
        await().during(Duration.ofSeconds(1)).atMost(ESPERA)
                .untilAsserted(() -> assertThat(linhasDoLance(bid)).isEmpty());
    }

    @Test
    void tabelaBidsEhAppendOnlyPeloBanco() {
        UUID leilao = criarLeilao("100");
        UUID bid = UUID.randomUUID();
        enviarLance("u1", leilao, "150.00", bid);
        aguardarLance(bid);

        assertThatThrownBy(() -> jdbc.update("UPDATE bids SET valor = 1 WHERE bid_id = ?", bid))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM bids WHERE bid_id = ?", bid))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void reenvioDoMesmoBidIdDevolveResultadoOriginalSemReprocessar() throws Exception {
        UUID leilao = criarLeilao("100");
        UUID bid = UUID.randomUUID();
        BlockingQueue<JsonNode> resultados = resultadosDe("u1");
        enviarLance("u1", leilao, "150.00", bid);
        aguardarLance(bid);
        Map<String, Object> estado = leilao(leilao);
        JsonNode original = proximo(resultados);

        assertThat(enviarLance("u1", leilao, "999.00", bid)).isEqualTo(202);

        JsonNode reenviado = proximo(resultados);
        assertThat(reenviado.get("duplicate").asBoolean()).isTrue();
        assertThat(reenviado.get("decision").asString()).isEqualTo("aceito");
        assertThat(reenviado.get("sequence").asLong()).isEqualTo(original.get("sequence").asLong());
        assertThat(linhasDoLance(bid)).hasSize(1);
        assertThat(leilao(leilao)).isEqualTo(estado);
    }

    @Test
    void mesmoBidIdEmOutroLeilaoNaoDuplicaLinha() throws Exception {
        UUID leilaoA = criarLeilao("100");
        UUID leilaoB = criarLeilao("100");
        UUID bid = UUID.randomUUID();
        BlockingQueue<JsonNode> resultados = resultadosDe("u1");
        enviarLance("u1", leilaoA, "150.00", bid);
        aguardarLance(bid);
        JsonNode original = proximo(resultados);

        enviarLance("u1", leilaoB, "150.00", bid);

        JsonNode reenviado = proximo(resultados);
        assertThat(reenviado.get("duplicate").asBoolean()).isTrue();
        assertThat(reenviado.get("auction_id").asString()).isEqualTo(original.get("auction_id").asString());
        assertThat(linhasDoLance(bid)).hasSize(1);
        assertThat(contarLances(leilaoB)).isZero();
        assertThat(leilao(leilaoB)).containsEntry("version", 0L);
    }

    @Test
    void lancesConcorrentesNoMesmoLeilaoSaoSerializadosEOMaiorValorVence() throws Exception {
        UUID leilao = criarLeilao("100");
        List<UUID> bids = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<Integer>> envios = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                UUID bid = UUID.randomUUID();
                bids.add(bid);
                String valor = String.valueOf(101 + i);
                String usuario = "u" + (i + 1);
                envios.add(pool.submit(() -> enviarLance(usuario, leilao, valor, bid)));
            }
            for (Future<Integer> f : envios) {
                assertThat(f.get(15, TimeUnit.SECONDS)).isEqualTo(202);
            }
        } finally {
            pool.shutdownNow();
        }

        await().atMost(ESPERA).untilAsserted(() -> assertThat(contarLances(leilao)).isEqualTo(10L));

        Map<String, Object> estado = leilao(leilao);
        java.math.BigDecimal maior = (java.math.BigDecimal) estado.get("highest_bid");
        assertThat(maior.compareTo(new java.math.BigDecimal("110"))).isZero();

        long aceitos = jdbc.queryForObject(
                "SELECT count(*) FROM bids WHERE auction_id = ? AND decision = 'aceito'", Long.class, leilao);
        assertThat(estado).containsEntry("version", aceitos);
        assertThat(aceitos).isGreaterThanOrEqualTo(1L);

        // nenhum bid_id duplicado e sequence crescente dentro de cada partição
        List<Map<String, Object>> linhas = jdbc.queryForList(
                "SELECT partition, sequence FROM bids WHERE auction_id = ? ORDER BY partition, sequence", leilao);
        assertThat(linhas).hasSize(10);
        for (int i = 1; i < linhas.size(); i++) {
            if (linhas.get(i).get("partition").equals(linhas.get(i - 1).get("partition"))) {
                assertThat((Long) linhas.get(i).get("sequence"))
                        .isGreaterThan((Long) linhas.get(i - 1).get("sequence"));
            }
        }
    }

    @Test
    void valoresIguaisEmLancesConcorrentesAceitaSoOPrimeiroDoLog() {
        UUID leilao = criarLeilao("100");
        UUID primeiro = UUID.randomUUID();
        UUID segundo = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> enviarLance("u1", leilao, "150.00", primeiro));
            Future<Integer> b = pool.submit(() -> enviarLance("u2", leilao, "150.00", segundo));
            assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(202);
            assertThat(b.get(15, TimeUnit.SECONDS)).isEqualTo(202);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }

        await().atMost(ESPERA).untilAsserted(() -> assertThat(contarLances(leilao)).isEqualTo(2L));
        long aceitos = jdbc.queryForObject(
                "SELECT count(*) FROM bids WHERE auction_id = ? AND decision = 'aceito'", Long.class, leilao);
        assertThat(aceitos).isEqualTo(1L);
        assertThat(leilao(leilao)).containsEntry("version", 1L);
    }
}
