package com.axion.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.axion.core.ToolResult;
import com.axion.tool.ActionType;
import com.axion.tool.FileSandboxProperties;
import com.axion.tool.HttpSandboxProperties;
import com.axion.tool.Sandbox;
import com.axion.tool.SandboxViolationException;
import com.axion.tool.ShellSandboxProperties;
import com.axion.tool.WhitelistSandbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
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
 * HttpTools 验收 harness（http_get/http_post）——MockWebServer 起本地假服务（004 先例，单测层不碰外网）： 坑十执行前先过
 * enforce(HTTP_REQUEST, url)、违规 IO 零发生（server.getRequestCount()==0 可观察）； POST JSON body 原样送达；响应体超
 * 1MB 明确报错；4xx/5xx 异常上抛（坑十一口径）。
 */
class HttpToolsTest {

  private MockWebServer server;
  private RestClient restClient;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    restClient = RestClient.builder().build();
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  @DisplayName("http_get：正常取回响应体")
  void getReturnsBody() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("weather-ok"));
    Sandbox sandbox = mock(Sandbox.class);
    HttpGetTool tool = new HttpGetTool(sandbox, restClient);

    ToolResult result =
        tool.execute(objectMapper.createObjectNode().put("url", server.url("/w").toString()));

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("weather-ok");
    verify(sandbox)
        .enforce(
            argThat(
                a ->
                    a.type() == ActionType.HTTP_REQUEST
                        && a.target().equals(server.url("/w").toString())));
  }

  @Test
  @DisplayName("http_post：JSON body 原样送达")
  void postSendsJsonBody() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
    HttpPostTool tool = new HttpPostTool(mock(Sandbox.class), restClient);

    ToolResult result =
        tool.execute(
            objectMapper
                .createObjectNode()
                .put("url", server.url("/p").toString())
                .put("body", "{\"k\":\"v\"}"));

    assertThat(result.success()).isTrue();
    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("POST");
    assertThat(request.getHeader("Content-Type")).contains("application/json");
    assertThat(request.getBody().readUtf8()).isEqualTo("{\"k\":\"v\"}");
  }

  @Test
  @DisplayName("坑十：违规时 IO 零发生——server 未收到任何请求")
  void violationZeroIo() {
    Sandbox sandbox = mock(Sandbox.class);
    org.mockito.Mockito.doThrow(new SandboxViolationException("域名不在白名单"))
        .when(sandbox)
        .enforce(any());
    HttpGetTool tool = new HttpGetTool(sandbox, restClient);

    assertThatThrownBy(
            () ->
                tool.execute(
                    objectMapper.createObjectNode().put("url", server.url("/w").toString())))
        .isInstanceOf(SandboxViolationException.class);
    assertThat(server.getRequestCount()).isZero(); // 校验在前，请求零发出
  }

  @Test
  @DisplayName("007 T014 接线回归：真实 WhitelistSandbox 白名单外域名——违规被拦且请求零发出")
  void whitelistSandboxBlocksRequest() {
    WhitelistSandbox sandbox =
        new WhitelistSandbox(
            Path.of("."),
            new FileSandboxProperties(List.of()),
            new ShellSandboxProperties(List.of(), List.of()),
            new HttpSandboxProperties(List.of("wttr.in")));
    HttpGetTool tool = new HttpGetTool(sandbox, restClient);

    assertThatThrownBy(
            () ->
                tool.execute(
                    objectMapper.createObjectNode().put("url", server.url("/w").toString())))
        .isInstanceOf(SandboxViolationException.class);
    assertThat(server.getRequestCount()).isZero(); // 真请求没发（不是 mock 拒绝，是白名单真拦）
  }

  @Test
  @DisplayName("响应体超 1MB：明确报错")
  void oversizedResponseFails() {
    server.enqueue(new MockResponse().setResponseCode(200).setBody("x".repeat(1_048_577)));
    HttpGetTool tool = new HttpGetTool(mock(Sandbox.class), restClient);

    ToolResult result =
        tool.execute(objectMapper.createObjectNode().put("url", server.url("/big").toString()));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("1MB");
  }

  @Test
  @DisplayName("坑十一口径：4xx/5xx 异常上抛不吞")
  void serverErrorPropagates() {
    server.enqueue(new MockResponse().setResponseCode(500));
    HttpGetTool tool = new HttpGetTool(mock(Sandbox.class), restClient);

    assertThatThrownBy(
            () ->
                tool.execute(
                    objectMapper.createObjectNode().put("url", server.url("/w").toString())))
        .isInstanceOf(RestClientException.class);
  }
}
