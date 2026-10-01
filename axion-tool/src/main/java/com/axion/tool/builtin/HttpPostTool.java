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
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 内置 Tool {@code http_post}（FR-4）：发起 HTTP POST 请求（JSON body）——execute 首行 {@code
 * sandbox.enforce(HTTP_REQUEST, url)} 先于请求（坑十）；contentType 默认 application/json、body 为 JSON
 * 字符串原样送达（form/文件上传明确不做）；响应体上限 1MB；4xx/5xx 异常上抛（坑十一口径）。 纯类交付无组件注解（G4-C1）。
 */
public class HttpPostTool implements OryxTool {

  private final Sandbox sandbox;
  private final RestClient restClient;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "RestClient 为装配处注入的 Spring 单例（协议栈不可复制），仅本类只读使用、不暴露引用（004 WebhookNotifyAdapter 同款先例）")
  public HttpPostTool(Sandbox sandbox, RestClient restClient) {
    this.sandbox = sandbox;
    this.restClient = restClient;
  }

  @Override
  public String getName() {
    return "http_post";
  }

  @Override
  public String getDescription() {
    return "发起一个 HTTP POST 请求（JSON body）并返回响应体文本。url 必填、body 为 JSON 字符串（可选）；" + "响应体超过 1MB 时明确报错。";
  }

  @Override
  public JsonSchema getInputSchema() {
    return new JsonSchema(
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "url", Map.of("type", "string", "description", "要请求的完整 URL"),
                "body", Map.of("type", "string", "description", "请求体 JSON 字符串（可选）")),
            "required",
            List.of("url")));
  }

  @Override
  public ToolResult execute(JsonNode input) {
    String url = input.get("url").asText();
    String body = input.has("body") ? input.get("body").asText() : "";
    sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url)); // 坑十：enforce 先于请求
    String response =
        restClient
            .post()
            .uri(URI.create(url))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(String.class);
    if (response == null) {
      return ToolResult.failure("响应体为空", false);
    }
    if (response.length() > HttpGetTool.MAX_RESPONSE_BYTES) {
      return ToolResult.failure("响应体超过 1MB 上限（" + response.length() + " 字节）", false);
    }
    return ToolResult.success(response);
  }
}
