package com.oryxos.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.oryxos.core.JsonSchema;
import com.oryxos.core.OryxTool;
import com.oryxos.core.ToolResult;
import com.oryxos.tool.ActionType;
import com.oryxos.tool.Sandbox;
import com.oryxos.tool.SandboxAction;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;

/**
 * 内置 Tool {@code http_get}（FR-4）：发起 HTTP GET 请求——execute 首行 {@code sandbox.enforce(HTTP_REQUEST,
 * url)} 先于请求（坑十）；响应体上限 1MB（超限明确报错）；4xx/5xx 异常 上抛（坑十一口径）。域名白名单规则本体归 23/24 节。纯类交付无组件注解（G4-C1）。
 */
public class HttpGetTool implements OryxTool {

  static final int MAX_RESPONSE_BYTES = 1_048_576; // 1MB 上限（参数规格表）

  private final Sandbox sandbox;
  private final RestClient restClient;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "RestClient 为装配处注入的 Spring 单例（协议栈不可复制），仅本类只读使用、不暴露引用（004 WebhookNotifyAdapter 同款先例）")
  public HttpGetTool(Sandbox sandbox, RestClient restClient) {
    this.sandbox = sandbox;
    this.restClient = restClient;
  }

  @Override
  public String getName() {
    return "http_get";
  }

  @Override
  public String getDescription() {
    return "发起一个 HTTP GET 请求并返回响应体文本。url 为完整请求地址（必填）；响应体超过 1MB 时明确报错。";
  }

  @Override
  public JsonSchema getInputSchema() {
    return new JsonSchema(
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("url", Map.of("type", "string", "description", "要请求的完整 URL")),
            "required",
            List.of("url")));
  }

  @Override
  public ToolResult execute(JsonNode input) {
    String url = input.get("url").asText();
    sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url)); // 坑十：enforce 先于请求
    String body = restClient.get().uri(URI.create(url)).retrieve().body(String.class);
    return checkSize(body);
  }

  ToolResult checkSize(String body) {
    if (body == null) {
      return ToolResult.failure("响应体为空", false);
    }
    if (body.length() > MAX_RESPONSE_BYTES) {
      return ToolResult.failure("响应体超过 1MB 上限（" + body.length() + " 字节）", false);
    }
    return ToolResult.success(body);
  }
}
