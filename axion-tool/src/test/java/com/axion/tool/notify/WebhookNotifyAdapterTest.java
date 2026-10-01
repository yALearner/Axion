package com.oryxos.tool.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * WebhookNotifyAdapter 验收 harness（第一批）——MockWebServer 起本地假 webhook（单测层，不算外网依赖）： 断言收到的 POST body 带
 * content、content-type JSON；URL 来自 NotifyTarget.config 而不是硬编码； 坑十一回归：webhook 5xx /
 * 网络失败时异常原样上抛、不静默吞掉。
 */
class WebhookNotifyAdapterTest {

  private MockWebServer server;
  private WebhookNotifyAdapter adapter;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    // 测试直构 RestClient（生产装配与 timeout 归第 20 节装配处，裁决 7）
    adapter = new WebhookNotifyAdapter(RestClient.builder().build());
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  @DisplayName("发送后假 webhook 收到 POST：body 为通用 text 格式（msgtype/text/content），content-type JSON")
  void sendPostsContentJson() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
    NotifyTarget target = targetFor(server.url("/hook").toString());

    adapter.send(target, "hello");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("POST");
    assertThat(request.getPath()).isEqualTo("/hook");
    assertThat(request.getHeader("Content-Type")).contains("application/json");
    // 40008 实锤修正：企业微信/钉钉要求 msgtype/text/content 包装，裸 {"content":...} 是 invalid message type
    assertThat(request.getBody().readUtf8())
        .contains("\"msgtype\":\"text\"")
        .contains("\"content\":\"hello\"");
  }

  @Test
  @DisplayName("URL 只从 NotifyTarget.config 取、不硬编码：换 config 换目标")
  void urlComesFromTargetConfig() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

    adapter.send(targetFor(server.url("/hook-a").toString()), "first");
    adapter.send(targetFor(server.url("/hook-b").toString()), "second");

    assertThat(server.takeRequest().getPath()).isEqualTo("/hook-a");
    assertThat(server.takeRequest().getPath()).isEqualTo("/hook-b");
  }

  @Test
  @DisplayName("坑十一回归：webhook 返回 5xx 时异常上抛、不静默吞掉")
  void throwsOnHttp500() {
    server.enqueue(new MockResponse().setResponseCode(500));
    NotifyTarget target = targetFor(server.url("/hook").toString());

    assertThatThrownBy(() -> adapter.send(target, "hello")).isInstanceOf(RestClientException.class);
  }

  @Test
  @DisplayName("坑十一回归：网络失败时异常上抛、不静默吞掉")
  void throwsOnNetworkFailure() throws Exception {
    String deadUrl = server.url("/hook").toString();
    server.shutdown();

    assertThatThrownBy(() -> adapter.send(targetFor(deadUrl), "hello"))
        .isInstanceOf(RestClientException.class);
  }

  private NotifyTarget targetFor(String url) {
    return new NotifyTarget(
        "webhook", Map.of(NotifyTarget.KEY_URL, url, NotifyTarget.KEY_NAME, "team-lark"));
  }
}
