package com.oryxos.memory;

import com.oryxos.core.LongTermMemoryStore;
import com.oryxos.core.MemoryScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 长期记忆 mem0 档（FR-5）：接自托管 Mem0（数据不出域），Java 侧 {@link RestClient} 直连 REST（005 Boot builder + timeout
 * 装配先例，零新第三方依赖——Mem0 无官方 Java SDK）。
 *
 * <p><strong>REST 协议（H3 核实 2026-09-06，自托管 OSS 版无 /v1/ 前缀）</strong>：append → {@code POST /memories}
 * （payload：user_id 固定租户 {@value #USER_ID} + messages 原文 + metadata.scope 分区）；load → {@code GET
 * /memories?user_id=} 全量拉回后按 metadata.scope 拆两区块、归档超 {@value #MAX_ARCHIVE_CHARS} 字客户端截断（与 markdown
 * 档同口径）；recall → {@code POST /search}（query + filters.user_id），只保留 metadata.scope=ARCHIVAL
 * 的命中（坑十八）。 凭证走 {@code X-API-Key} 头（自托管应 HTTPS——传输加密口径，需求文档自审 #8）。
 *
 * <p>Mem0 自带的事实抽取是其外部能力（用不用取决于是否切该档，技术方案 §5.5 口径）；missing scope 按 ARCHIVAL 处理（本类写入的条目 恒带 scope）。非
 * 2xx 异常上抛不吞（FR-6 快速失败，不静默返回空记忆、不自动降级换档）；每轮一次 REST 调用的延迟口径诚实标注（自审 #6，缓存与坑十五
 * 张力记录，信号驱动再上）。纯类交付无组件注解（G4-C1 延续），装配处显式 {@code @Bean}。
 */
public class Mem0MemoryStore implements LongTermMemoryStore {

  /** OryxOS 底座单一租户标识：接口契约无 user 概念（append/load/recall 不带用户），mem0 按 user_id 分库——固定租户收口。 */
  private static final String USER_ID = "oryxos";

  /** 归档区客户端截断阈值（字符数）——与 markdown 档同口径（坑十六）。 */
  private static final int MAX_ARCHIVE_CHARS = 4000;

  /** recall 单次返回上限（千条级归档取最相关 20 条）。 */
  private static final int SEARCH_TOP_K = 20;

  private final RestClient restClient;

  public Mem0MemoryStore(RestClient restClient, String baseUrl, String apiKey) {
    this.restClient =
        restClient.mutate().baseUrl(baseUrl).defaultHeader("X-API-Key", apiKey).build();
  }

  @Override
  public void append(String content, MemoryScope scope) {
    Map<String, Object> body =
        Map.of(
            "user_id",
            USER_ID,
            "messages",
            List.of(Map.of("role", "user", "content", content)),
            "metadata",
            Map.of("scope", scope.name()));
    try {
      restClient
          .post()
          .uri("/memories")
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      throw new IllegalStateException("Mem0 写入失败: " + e.getMessage(), e);
    }
  }

  @Override
  public String load() {
    List<Map<String, Object>> memories = getAll();
    List<String> core = new ArrayList<>();
    List<String> archive = new ArrayList<>();
    for (Map<String, Object> memory : memories) {
      String content = String.valueOf(memory.get("memory"));
      if (isCore(memory)) {
        core.add(content);
      } else {
        archive.add(content); // 缺 scope 按 ARCHIVAL（本类写入恒带 scope；外部条目保守归归档）
      }
    }
    String archiveText = String.join(System.lineSeparator(), archive);
    if (archiveText.length() > MAX_ARCHIVE_CHARS) {
      archiveText = archiveText.substring(archiveText.length() - MAX_ARCHIVE_CHARS); // 坑十六：只裁归档
    }
    return MarkdownMemoryStore.CORE_HEADER
        + System.lineSeparator()
        + String.join(System.lineSeparator(), core)
        + System.lineSeparator()
        + MarkdownMemoryStore.ARCHIVE_HEADER
        + System.lineSeparator()
        + archiveText;
  }

  @Override
  public List<String> recallByKeyword(String keyword) {
    if (keyword == null || keyword.isBlank()) {
      return List.of();
    }
    Map<String, Object> body =
        Map.of("query", keyword, "filters", Map.of("user_id", USER_ID), "top_k", SEARCH_TOP_K);
    try {
      Map<String, Object> response =
          restClient
              .post()
              .uri("/search")
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .body(new ParameterizedTypeReference<Map<String, Object>>() {});
      if (response == null) {
        throw new IllegalStateException("Mem0 检索返回空响应");
      }
      List<Map<String, Object>> results = castResults(response.get("results"));
      return results.stream()
          .filter(m -> !isCore(m)) // 坑十八：只搜归档（核心区命中过滤掉）
          .map(m -> String.valueOf(m.get("memory")))
          .toList();
    } catch (RestClientException e) {
      throw new IllegalStateException("Mem0 检索失败: " + e.getMessage(), e);
    }
  }

  private List<Map<String, Object>> getAll() {
    try {
      List<Map<String, Object>> memories =
          restClient
              .get()
              .uri("/memories?user_id={userId}", USER_ID)
              .retrieve()
              .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
      if (memories == null) {
        throw new IllegalStateException("Mem0 返回空响应（服务异常）");
      }
      return memories;
    } catch (RestClientException e) {
      throw new IllegalStateException("Mem0 读取失败: " + e.getMessage(), e);
    }
  }

  private boolean isCore(Map<String, Object> memory) {
    Object metadata = memory.get("metadata");
    if (metadata instanceof Map<?, ?> map) {
      return MemoryScope.CORE.name().equals(String.valueOf(map.get("scope")));
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> castResults(Object results) {
    if (results instanceof List<?> list) {
      return (List<Map<String, Object>>) list;
    }
    return List.of();
  }
}
