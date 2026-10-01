package com.oryxos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.core.MemoryScope;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Mem0MemoryStore 验收 harness——mock HTTP 层（MockWebServer 假 Mem0 服务，005 先例，单测不碰外网）：append/load/recall
 * 的 REST 翻译（自托管 OSS 协议：无 /v1/ 前缀——POST /memories / GET /memories / POST /search，H3 核实
 * 2026-09-06）、凭证 X-API-Key 头、非 2xx 异常上抛不吞。
 */
class Mem0MemoryStoreTest {

  private MockWebServer server;
  private Mem0MemoryStore store;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    store =
        new Mem0MemoryStore(RestClient.builder().build(), server.url("/").toString(), "test-key");
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  @DisplayName("append → POST /memories：messages 原文、user_id 固定租户、scope 落 metadata、X-API-Key 凭证头")
  void appendTranslatesToAddMemory() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

    store.append("项目用 Spring Boot", MemoryScope.CORE);

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("POST");
    assertThat(request.getPath()).isEqualTo("/memories"); // 自托管 OSS 无 /v1/ 前缀（H3 核实）
    assertThat(request.getHeader("X-API-Key")).isEqualTo("test-key");
    Map<String, Object> body =
        objectMapper.readValue(
            request.getBody().readUtf8(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
    assertThat(body).containsEntry("user_id", "oryxos");
    assertThat(((Map<?, ?>) ((List<?>) body.get("messages")).get(0)).get("content"))
        .isEqualTo("项目用 Spring Boot");
    Object metadata = body.get("metadata");
    assertThat(metadata).isInstanceOf(Map.class);
    assertThat(((Map<?, ?>) metadata).get("scope")).isEqualTo("CORE");
  }

  @Test
  @DisplayName("load → GET /memories：按 metadata.scope 拆分核心/归档两区块、归档超 4000 字客户端截断")
  void loadTranslatesToGetAll() throws Exception {
    server.enqueue(
        json(
            List.of(
                Map.of("memory", "核心条目", "metadata", Map.of("scope", "CORE")),
                Map.of("memory", "归档条目", "metadata", Map.of("scope", "ARCHIVAL")))));

    String loaded = store.load();

    assertThat(loaded).contains("## 核心记忆").contains("核心条目").contains("## 归档记忆").contains("归档条目");
    assertThat(loaded.indexOf("核心条目")).isLessThan(loaded.indexOf("## 归档记忆"));
    assertThat(loaded.indexOf("归档条目")).isGreaterThan(loaded.indexOf("## 归档记忆"));
    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("GET");
    assertThat(request.getPath()).contains("/memories").contains("user_id=oryxos");
  }

  @Test
  @DisplayName("recall → POST /search：query 原样、只返回 metadata.scope=ARCHIVAL 的命中（坑十八）")
  void recallTranslatesToSearchAndFiltersArchive() throws Exception {
    server.enqueue(
        json(
            Map.of(
                "results",
                List.of(
                    Map.of("memory", "归档命中词X", "metadata", Map.of("scope", "ARCHIVAL")),
                    Map.of("memory", "核心命中词X", "metadata", Map.of("scope", "CORE"))))));

    List<String> hits = store.recallByKeyword("词X");

    assertThat(hits).containsExactly("归档命中词X"); // 坑十八：核心区命中被过滤
    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("POST");
    assertThat(request.getPath()).isEqualTo("/search");
    Map<String, Object> body =
        objectMapper.readValue(
            request.getBody().readUtf8(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
    assertThat(body).containsEntry("query", "词X");
  }

  @Test
  @DisplayName("非 2xx 异常上抛不吞（FR-6 快速失败：后端故障明确报错）")
  void non2xxFailsFast() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

    assertThatThrownBy(() -> store.load()).isInstanceOf(IllegalStateException.class);
    server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
    assertThatThrownBy(() -> store.recallByKeyword("x")).isInstanceOf(IllegalStateException.class);
  }

  private MockResponse json(Object body) throws Exception {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(objectMapper.writeValueAsString(body));
  }
}
