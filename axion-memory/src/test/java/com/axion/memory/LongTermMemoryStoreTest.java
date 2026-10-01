package com.oryxos.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.core.LongTermMemoryStore;
import com.oryxos.core.MemoryScope;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;

/**
 * 参数化契约测试（自审 #5）：遍历 Markdown/Sqlite/Mem0 三档实现钉死四条行为契约（坑十五不缓存 / 坑十六核心不截断 / 坑十七 scope 路由 /
 * 坑十八只搜归档）——三档行为等价性防线，防止某一档实现偏差漏网。
 */
class LongTermMemoryStoreTest {

  /** 静态 @TempDir（实例字段注入与 @MethodSource 静态方法不兼容）；每次 stores() 调用在其下建独立子目录保证各测试方法隔离。 */
  @TempDir static Path tempDir;

  private static final List<MockWebServer> mem0Servers = new ArrayList<>();

  /** 三档实现装配：markdown 用临时文件；sqlite 用临时库 + 手工 schema.sql（坑八）；mem0 用有状态 MockWebServer 假服务。 */
  static Stream<Arguments> stores() throws Exception {
    Path dir = Files.createTempDirectory(tempDir, "store-");
    List<Map<String, Object>> mem0Memories = new ArrayList<>();
    MockWebServer mem0Server = new MockWebServer();
    mem0Server.start();
    mem0Server.setDispatcher(fakeMem0Dispatcher(mem0Memories));
    mem0Servers.add(mem0Server);

    return Stream.of(
        Arguments.of("markdown", new MarkdownMemoryStore(dir.resolve("MEMORY.md"))),
        Arguments.of("sqlite", sqliteStore(dir.resolve("contract.db"))),
        Arguments.of(
            "mem0",
            new Mem0MemoryStore(
                RestClient.builder().build(), mem0Server.url("/").toString(), "test-key")));
  }

  @AfterAll
  static void tearDown() throws Exception {
    for (MockWebServer server : mem0Servers) {
      server.shutdown();
    }
  }

  @ParameterizedTest(name = "坑十五不缓存 [{0}]")
  @MethodSource("stores")
  @DisplayName("坑十五：append 后立刻 load/recall 命中——每次重新读不缓存")
  void noCachingAfterAppend(String name, LongTermMemoryStore store) {
    store.append("刚记的事", MemoryScope.ARCHIVAL);

    assertThat(store.load()).contains("刚记的事");
    assertThat(store.recallByKeyword("刚记的事")).isNotEmpty();
  }

  @ParameterizedTest(name = "坑十六核心永不截断 [{0}]")
  @MethodSource("stores")
  @DisplayName("坑十六：核心区一字不少、截断只裁归档区（保留最近的）")
  void coreNeverTruncated(String name, LongTermMemoryStore store) {
    store.append("用户叫小王，偏好用 Java", MemoryScope.CORE);
    for (int i = 0; i < 250; i++) {
      // 内容加长：三档的截断阈值不同（markdown/mem0 按 4000 字、sqlite 按 200 条），
      // 统一灌到各自阈值之上，断言"最旧被裁、最新保留"三档一致
      store.append("归档流水 " + i + " 填充填充填充填充填充填充填充填充", MemoryScope.ARCHIVAL);
    }

    String loaded = store.load();

    assertThat(loaded).contains("用户叫小王，偏好用 Java"); // 核心区完整——"始终在场"的底线
    assertThat(loaded).doesNotContain("归档流水 0 "); // 归档区最早的内容被裁掉了
    assertThat(loaded).contains("归档流水 249 "); // 保留的是最近的
  }

  @ParameterizedTest(name = "坑十七 scope 路由 [{0}]")
  @MethodSource("stores")
  @DisplayName("坑十七：scope 路由正确区块——CORE 进核心区、ARCHIVAL 进归档区")
  void scopeRoutesToCorrectSection(String name, LongTermMemoryStore store) {
    store.append("核心条目", MemoryScope.CORE);
    store.append("归档条目", MemoryScope.ARCHIVAL);

    String loaded = store.load();
    assertThat(loaded).contains("核心条目").contains("归档条目");
    assertThat(loaded.indexOf("核心条目")).isLessThan(loaded.indexOf("## 归档记忆"));
    assertThat(loaded.indexOf("归档条目")).isGreaterThan(loaded.indexOf("## 归档记忆"));
  }

  @ParameterizedTest(name = "坑十八只搜归档 [{0}]")
  @MethodSource("stores")
  @DisplayName("坑十八：recall 只搜归档区——核心区同关键词不命中")
  void recallOnlySearchesArchiveSection(String name, LongTermMemoryStore store) {
    store.append("核心区的独有词X", MemoryScope.CORE);
    store.append("归档区的独有词X", MemoryScope.ARCHIVAL);

    assertThat(store.recallByKeyword("独有词X")).hasSize(1);
    assertThat(store.recallByKeyword("独有词X").get(0)).contains("归档区的");
  }

  private static LongTermMemoryStore sqliteStore(Path dbFile) throws Exception {
    DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String schema =
        new String(
            LongTermMemoryStoreTest.class.getResourceAsStream("/schema.sql").readAllBytes(),
            StandardCharsets.UTF_8);
    for (String sql : schema.split(";", -1)) {
      if (!sql.isBlank()) {
        jdbc.execute(sql);
      }
    }
    return new SqliteMemoryStore(dataSource);
  }

  /** 有状态假 Mem0 服务：POST /memories 存、GET /memories 全量回、POST /search 按 query 过滤——只模拟协议翻译所需行为。 */
  private static Dispatcher fakeMem0Dispatcher(List<Map<String, Object>> mem0Memories) {
    ObjectMapper objectMapper = new ObjectMapper();
    java.util.function.Function<Object, MockResponse> json =
        body -> {
          try {
            return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(objectMapper.writeValueAsString(body));
          } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("假 Mem0 服务序列化失败", e);
          }
        };
    return new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
        try {
          String path = request.getPath();
          if ("POST".equals(request.getMethod()) && "/memories".equals(path)) {
            JsonNode body = objectMapper.readTree(request.getBody().readUtf8());
            String content = body.path("messages").path(0).path("content").asText("");
            String scope = body.path("metadata").path("scope").asText("");
            mem0Memories.add(Map.of("memory", content, "metadata", Map.of("scope", scope)));
            return json.apply(Map.of("results", List.of()));
          }
          if ("GET".equals(request.getMethod()) && path.startsWith("/memories")) {
            return json.apply(mem0Memories);
          }
          if ("POST".equals(request.getMethod()) && "/search".equals(path)) {
            JsonNode body = objectMapper.readTree(request.getBody().readUtf8());
            String query = body.path("query").asText("");
            List<Map<String, Object>> hits =
                mem0Memories.stream()
                    .filter(m -> String.valueOf(m.get("memory")).contains(query))
                    .filter(m -> "ARCHIVAL".equals(((Map<?, ?>) m.get("metadata")).get("scope")))
                    .toList();
            return json.apply(Map.of("results", hits));
          }
          return new MockResponse().setResponseCode(404);
        } catch (java.io.IOException e) {
          return new MockResponse().setResponseCode(500).setBody("fake-mem0-error");
        }
      }
    };
  }
}
