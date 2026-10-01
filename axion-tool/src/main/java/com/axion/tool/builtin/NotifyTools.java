package com.oryxos.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.oryxos.core.JsonSchema;
import com.oryxos.core.OryxTool;
import com.oryxos.core.ToolResult;
import com.oryxos.tool.ActionType;
import com.oryxos.tool.Sandbox;
import com.oryxos.tool.SandboxAction;
import com.oryxos.tool.notify.NotifyChannelAdapter;
import com.oryxos.tool.notify.NotifyChannelRegistry;
import com.oryxos.tool.notify.NotifyTarget;
import java.util.List;
import java.util.Map;

/**
 * 内置 Tool {@code notify}（FR-6）：把一条消息推送到全局注册表中按名引用的通知渠道——入站有 Channel Adapter
 * 负责"消息怎么进来"，这里补出站的对称物"消息怎么出去"（技术方案 §6.8）。这是 Sandbox 接口的第一个消费方 （002 contracts/sandbox.md 消费方列表）。
 *
 * <p>execute 四步顺序钉死：① 按 channel 名从注册表解析 {@link NotifyTarget}（缺省口径：恰好一条渠道才允许， 见 {@link
 * NotifyChannelRegistry}）② 按 {@code target.channelType()} 从装配处显式 {@code Map<channelType,
 * NotifyChannelAdapter>} 选 adapter（宪法 III 哲学，不靠容器扫描），未知 type 明确报错 ③ {@code sandbox.enforce(new
 * SandboxAction(HTTP_REQUEST, url))} <b>先于 send</b>（坑十：顺序反了就是白名单 被"往外推"绕过）④ {@code
 * adapter.send(target, content)}。成功返回"已推送到 &lt;渠道名&gt;"——审计带渠道名， 可查出推给了谁，不裸记"已推送"。
 *
 * <p>审计：成功/失败复用 ToolExecutor 既有路径落 {@code tool_invocations}（宪法 V），本类不新增审计逻辑； 异常原样上抛。注册进工具集归第 20 节
 * ToolRegistry（003 FR-10 口径）；不加 {@code @Component} （G4-C1 钉死：Sandbox/Map/Registry bean 均未就位，第 20
 * 节装配处显式 {@code @Bean}）。
 */
public class NotifyTools implements OryxTool {

  private final Sandbox sandbox;
  private final Map<String, NotifyChannelAdapter> adapters;
  private final NotifyChannelRegistry registry;

  public NotifyTools(
      Sandbox sandbox, Map<String, NotifyChannelAdapter> adapters, NotifyChannelRegistry registry) {
    this.sandbox = sandbox;
    this.adapters = Map.copyOf(adapters);
    this.registry = registry;
  }

  @Override
  public String getName() {
    return "notify";
  }

  @Override
  public String getDescription() {
    return "把一条消息推送到通知渠道。content 为消息内容（必填）；channel 为渠道注册名（可选，" + "注册表恰好一条渠道时可省略，多条时必须显式指定）。";
  }

  @Override
  public JsonSchema getInputSchema() {
    return new JsonSchema(
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "content",
                Map.of("type", "string", "description", "要推送的消息内容"),
                "channel",
                Map.of("type", "string", "description", "通知渠道注册名（可选，恰好一条渠道时可省略）")),
            "required",
            List.of("content")));
  }

  @Override
  public ToolResult execute(JsonNode input) {
    // S1 复盘修复（2026-09-05 拍板）：content 必填校验——缺失/JSON null/空串 → 明确失败
    // （走 ToolExecutor 审计 success=false，错误信息对 LLM 可读；JSON null 的 asText() 是字面 "null"，不校验会真推 "null"）
    JsonNode contentNode = input.get("content");
    if (contentNode == null || contentNode.isNull() || contentNode.asText().isBlank()) {
      return ToolResult.failure("参数 content 必填且不能为空", false);
    }
    String content = contentNode.asText();
    String channel = input.has("channel") ? input.get("channel").asText() : null;

    NotifyTarget target = registry.resolve(channel);

    NotifyChannelAdapter adapter = adapters.get(target.channelType());
    if (adapter == null) {
      throw new IllegalArgumentException("未知通知渠道类型: " + target.channelType());
    }

    sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, target.url()));

    adapter.send(target, content);
    return ToolResult.success("已推送到 " + target.name());
  }
}
