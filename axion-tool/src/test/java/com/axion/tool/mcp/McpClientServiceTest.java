package com.oryxos.tool.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.tool.ToolRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.ConnectException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * McpClientService 验收 harness——坑十三（课件 §四最值钱测试之二）：某个 MCP server 失联时只 WARN、connectAll 不抛、好 server
 * 的工具照常注册、坏 server 的不注册——外部依赖的可用性不是自己的可用性。 经子类覆写 connect() 注入 mock 客户端（单测层不碰网）。
 */
class McpClientServiceTest {

  @Test
  @DisplayName("坑十三：某个 MCP server 失联不能拖垮启动和其他工具")
  void failedServerDoesNotBreakOthers() {
    ToolRegistry registry = new ToolRegistry();
    McpClientService service =
        new StubConnectService(registry, List.of(config("good-server"), config("bad-server")));

    service.connectAll(); // 不抛异常

    assertThat(registry.contains("github_pr_list")).isTrue(); // 好的 server 照常注册
    assertThat(registry.contains("bad_mcp_tool")).isFalse();
  }

  @Test
  @DisplayName("tools/list 返回的工具逐个包装注册（来源无感知）")
  void listToolsWrappedAndRegistered() {
    ToolRegistry registry = new ToolRegistry();
    McpClientService service = new StubConnectService(registry, List.of(config("good-server")));

    service.connectAll();

    assertThat(registry.contains("github_pr_list")).isTrue();
  }

  @Test
  @DisplayName("配置为空列表：connectAll 正常返回（无 server 也是合法状态）")
  void emptyConfigIsFine() {
    ToolRegistry registry = new ToolRegistry();
    McpClientService service = new StubConnectService(registry, List.of());

    service.connectAll();

    assertThat(registry.all()).isEmpty();
  }

  /** 测试子类：覆写 connect() 与配置来源，注入 mock 客户端（按 server 名分派好/坏）。 */
  private static final class StubConnectService extends McpClientService {

    private final List<McpServerConfig> configs;

    StubConnectService(ToolRegistry registry, List<McpServerConfig> configs) {
      super(registry, new ObjectMapper());
      this.configs = configs;
    }

    @Override
    protected List<McpServerConfig> loadConfigs() {
      return configs;
    }

    @Override
    protected McpSyncClient connect(McpServerConfig cfg) throws Exception {
      if ("bad-server".equals(cfg.name())) {
        throw new ConnectException("refused");
      }
      McpSyncClient client = mock(McpSyncClient.class);
      when(client.initialize())
          .thenReturn(new McpSchema.InitializeResult("2025-03-26", null, null, null));
      when(client.listTools())
          .thenReturn(
              new McpSchema.ListToolsResult(
                  List.of(
                      new McpSchema.Tool(
                          "github_pr_list",
                          null,
                          "list PRs",
                          new McpSchema.JsonSchema(
                              "object", Map.of(), List.of(), false, Map.of(), Map.of()),
                          null,
                          null,
                          null)),
                  null));
      return client;
    }
  }

  private McpServerConfig config(String name) {
    return new McpServerConfig(name, "stdio", "npx", List.of(), Map.of());
  }
}
