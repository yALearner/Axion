package com.axion.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axion.core.ToolResult;
import com.axion.tool.ActionType;
import com.axion.tool.FileSandboxProperties;
import com.axion.tool.HttpSandboxProperties;
import com.axion.tool.Sandbox;
import com.axion.tool.SandboxViolationException;
import com.axion.tool.ShellSandboxProperties;
import com.axion.tool.WhitelistSandbox;
import com.axion.tool.notify.NotifyChannelAdapter;
import com.axion.tool.notify.NotifyChannelRegistry;
import com.axion.tool.notify.NotifyTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * NotifyTools 验收 harness（第二批）——mock Sandbox/adapter Map/Registry： 成功返回带渠道名（审计带渠道名）；渠道未配置 / 缺省多条 /
 * 未知 channelType → 明确报错； 坑十回归：enforce 先于 send 被调用（InOrder 顺序断言）； 违规路径（US3）：enforce 拒绝时 send
 * 不得被调用、异常明确上抛。
 */
class NotifyToolsTest {

  private static final String HOOK_URL = "https://open.feishu.cn/open-apis/bot/v2/hook/xxx";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Sandbox sandbox = mock(Sandbox.class);
  private final NotifyChannelRegistry registry = mock(NotifyChannelRegistry.class);
  private final NotifyChannelAdapter adapter = mock(NotifyChannelAdapter.class);

  private NotifyTools notifyTools;

  @BeforeEach
  void setUp() {
    // 装配处显式映射（拍板，宪法 III 哲学）：channelType -> adapter，不靠容器扫描
    Map<String, NotifyChannelAdapter> adapters = Map.of("webhook", adapter);
    notifyTools = new NotifyTools(sandbox, adapters, registry);
  }

  @Test
  @DisplayName("发送前必须先过白名单校验（坑十，InOrder 顺序断言）")
  void enforcesBeforeSend() {
    NotifyTarget target = teamLarkTarget();
    when(registry.resolve("team-lark")).thenReturn(target);

    notifyTools.execute(
        objectMapper.createObjectNode().put("content", "hello").put("channel", "team-lark"));

    InOrder inOrder = inOrder(sandbox, adapter);
    inOrder.verify(sandbox).enforce(argThat(a -> a.type() == ActionType.HTTP_REQUEST));
    inOrder.verify(adapter).send(any(), eq("hello")); // 校验在前，发送在后——顺序反了就是漏洞
  }

  @Test
  @DisplayName("成功：返回「已推送到 <渠道名>」（审计带渠道名，不裸记已推送）")
  void successReturnsChannelName() {
    when(registry.resolve("team-lark")).thenReturn(teamLarkTarget());

    ToolResult result =
        notifyTools.execute(
            objectMapper.createObjectNode().put("content", "测试消息").put("channel", "team-lark"));

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("已推送到 team-lark");
    verify(adapter).send(any(), eq("测试消息"));
  }

  @Test
  @DisplayName("content 缺失：明确失败不 NPE（错误信息对 LLM 可读），不落任何外发")
  void missingContentFails() {
    ToolResult result = notifyTools.execute(objectMapper.createObjectNode());

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("content");
    verify(sandbox, never()).enforce(any());
    verify(adapter, never()).send(any(), any());
  }

  @Test
  @DisplayName("content 为 JSON null：明确失败，不推送字面 'null'（S1 复盘回归）")
  void nullContentFails() {
    ToolResult result = notifyTools.execute(objectMapper.createObjectNode().putNull("content"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("content");
    verify(adapter, never()).send(any(), any());
  }

  @Test
  @DisplayName("content 为空串/纯空白：明确失败（S1 复盘回归）")
  void blankContentFails() {
    ToolResult result = notifyTools.execute(objectMapper.createObjectNode().put("content", "   "));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("content");
    verify(adapter, never()).send(any(), any());
  }

  @Test
  @DisplayName("渠道未配置：明确报错，不静默失败")
  void missingChannelThrows() {
    when(registry.resolve("no-such-channel"))
        .thenThrow(new IllegalArgumentException("通知渠道未注册: no-such-channel"));

    assertThatThrownBy(
            () ->
                notifyTools.execute(
                    objectMapper
                        .createObjectNode()
                        .put("content", "hello")
                        .put("channel", "no-such-channel")))
        .isInstanceOf(IllegalArgumentException.class);
    verify(adapter, never()).send(any(), any());
  }

  @Test
  @DisplayName("channel 缺省：注册表恰好一条渠道时取它（拍板口径）")
  void defaultChannelTakenWhenRegistryResolves() {
    when(registry.resolve(null)).thenReturn(teamLarkTarget());

    ToolResult result =
        notifyTools.execute(objectMapper.createObjectNode().put("content", "hello"));

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("已推送到 team-lark");
  }

  @Test
  @DisplayName("channel 缺省：注册表多条时报错（拍板口径，推错群是不可见的错误）")
  void defaultChannelRejectedWhenMultiple() {
    when(registry.resolve(null))
        .thenThrow(new IllegalArgumentException("注册表存在多条通知渠道，必须显式指定 channel"));

    assertThatThrownBy(
            () -> notifyTools.execute(objectMapper.createObjectNode().put("content", "hello")))
        .isInstanceOf(IllegalArgumentException.class);
    verify(adapter, never()).send(any(), any());
  }

  @Test
  @DisplayName("adapter Map 无对应 channelType：明确报错")
  void unknownChannelTypeThrows() {
    when(registry.resolve("weird"))
        .thenReturn(new NotifyTarget("weird", Map.of("url", HOOK_URL, "name", "weird")));

    assertThatThrownBy(
            () ->
                notifyTools.execute(
                    objectMapper
                        .createObjectNode()
                        .put("content", "hello")
                        .put("channel", "weird")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("weird");
    verify(adapter, never()).send(any(), any());
  }

  @Test
  @DisplayName("违规路径（US3）：enforce 拒绝时 send 不得被调用、异常明确上抛")
  void violationBlocksSend() {
    when(registry.resolve("team-lark")).thenReturn(teamLarkTarget());
    org.mockito.Mockito.doThrow(new SandboxViolationException("域名不在白名单: " + HOOK_URL))
        .when(sandbox)
        .enforce(any());

    assertThatThrownBy(
            () ->
                notifyTools.execute(
                    objectMapper
                        .createObjectNode()
                        .put("content", "hello")
                        .put("channel", "team-lark")))
        .isInstanceOf(SandboxViolationException.class);
    verify(adapter, never()).send(any(), any());
  }

  @Test
  @DisplayName("007 T014 接线回归：真实 WhitelistSandbox 白名单外域名——违规被拦且 send 不得被调用")
  void whitelistSandboxBlocksSend() {
    WhitelistSandbox whitelist =
        new WhitelistSandbox(
            Path.of("."),
            new FileSandboxProperties(List.of()),
            new ShellSandboxProperties(List.of(), List.of()),
            new HttpSandboxProperties(List.of("wttr.in")));
    NotifyTools tool = new NotifyTools(whitelist, Map.of("webhook", adapter), registry);
    when(registry.resolve("team-lark")).thenReturn(teamLarkTarget());

    assertThatThrownBy(
            () ->
                tool.execute(
                    objectMapper
                        .createObjectNode()
                        .put("content", "hello")
                        .put("channel", "team-lark")))
        .isInstanceOf(SandboxViolationException.class);
    verify(adapter, never()).send(any(), any()); // 真推送没发生（不是 mock 拒绝，是白名单真拦）
  }

  @Test
  @DisplayName("工具名与参数 schema：notify，content 必填、channel 可选")
  void nameAndInputSchema() {
    assertThat(notifyTools.getName()).isEqualTo("notify");
    assertThat(notifyTools.getDescription()).isNotBlank();
    Map<String, Object> schema = notifyTools.getInputSchema().value();
    assertThat(schema).containsKey("properties");
    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertThat(properties).containsKeys("content", "channel");
    assertThat(schema.get("required")).isEqualTo(java.util.List.of("content"));
  }

  private NotifyTarget teamLarkTarget() {
    return new NotifyTarget(
        "webhook", Map.of(NotifyTarget.KEY_URL, HOOK_URL, NotifyTarget.KEY_NAME, "team-lark"));
  }
}
