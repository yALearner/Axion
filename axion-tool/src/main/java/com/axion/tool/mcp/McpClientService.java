package com.oryxos.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.tool.ToolRegistry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * MCP 方式二客户端（FR-5）：启动时读 {@code .oryxos/mcp_servers.yaml}，逐个 stdio 连接、{@code tools/list} 拉取、包装成
 * {@link com.oryxos.tool.mcp.McpToolAdapter} 注册进 ToolRegistry。
 *
 * <p>坑十三：连接失败只 WARN 带 server 名、不抛、不拖垮启动——外部依赖的可用性不是自己的可用性；其余工具照常 注册。配置缺失/空列表 = 正常启动（无 MCP server
 * 是合法状态）；yaml 结构非法 → 明确报错阻断启动（本地配置 错误不静默）。
 *
 * <p>可 {@code @Component}（G4-C1 口径：依赖 ToolRegistry bean 就位后扫描拾取，boot 扫描 com.oryxos 全树）； 业务层同步门面调用，零
 * Reactor 代码（宪法 VII 口径）。
 */
@Component
public class McpClientService {

  private static final Logger LOG = LoggerFactory.getLogger(McpClientService.class);

  private final ToolRegistry toolRegistry;
  private final ObjectMapper objectMapper;

  @Autowired
  public McpClientService(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
    this.toolRegistry = toolRegistry;
    this.objectMapper = objectMapper.copy();
  }

  /** 启动时连接所有配置的 MCP server；单个失败只 WARN 跳过（坑十三）。 */
  @jakarta.annotation.PostConstruct
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "server 名为管理员 yaml 配置值（非对话用户输入）；WARN 带名是坑十三的明确诊断要求（课件逐字，004 同款口径）")
  public void connectAll() {
    for (McpServerConfig cfg : loadConfigs()) {
      try {
        McpSyncClient client = connect(cfg);
        client.initialize();
        for (McpSchema.Tool spec : client.listTools().tools()) {
          toolRegistry.register(new McpToolAdapter(client, spec, objectMapper));
        }
      } catch (Exception e) {
        LOG.warn("MCP server {} 连接失败，跳过它的工具", cfg.name(), e);
      }
    }
  }

  /** 测试 seam：子类覆写注入 mock 客户端。 */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
      justification = "测试 seam 需允许子类抛出任意连接异常以模拟失联场景（坑十三回归测试）；生产实现不抛检查异常")
  protected McpSyncClient connect(McpServerConfig cfg) throws Exception {
    ServerParameters params =
        ServerParameters.builder(cfg.command())
            .args(cfg.args())
            .env(resolveEnv(cfg.env(), cfg.name()))
            .build();
    return McpClient.sync(new StdioClientTransport(params, new JacksonMcpJsonMapper(objectMapper)))
        .build();
  }

  /** 测试 seam：子类覆写配置来源。 */
  protected List<McpServerConfig> loadConfigs() {
    Path configFile = Path.of(".oryxos", "mcp_servers.yaml");
    if (!Files.exists(configFile)) {
      return List.of();
    }
    return parseConfig(configFile);
  }

  @SuppressWarnings("unchecked")
  private List<McpServerConfig> parseConfig(Path configFile) {
    Map<String, Object> root = new Yaml().load(readQuietly(configFile));
    if (root == null || root.get("mcp_servers") == null) {
      return List.of();
    }
    Object servers = root.get("mcp_servers");
    if (!(servers instanceof List<?> list)) {
      throw new IllegalArgumentException("mcp_servers.yaml 结构非法: mcp_servers 必须是列表");
    }
    List<McpServerConfig> result = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> raw)) {
        throw new IllegalArgumentException("mcp_servers.yaml 结构非法: 列表项必须是映射");
      }
      Map<String, Object> map = (Map<String, Object>) raw;
      String name = stringOf(map, "name");
      String transport = map.getOrDefault("transport", "stdio").toString();
      if (!"stdio".equals(transport)) {
        throw new IllegalArgumentException(
            "MCP server [" + name + "] 的 transport 仅支持 stdio（SSE 放扩展阶段）: " + transport);
      }
      Object args = map.getOrDefault("args", List.of());
      Object env = map.getOrDefault("env", Map.of());
      if (!(args instanceof List<?> argList) || !(env instanceof Map<?, ?> envMap)) {
        throw new IllegalArgumentException("MCP server [" + name + "] 的 args/env 结构非法");
      }
      Map<String, String> envStrings =
          envMap.entrySet().stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      e -> e.getKey().toString(), e -> String.valueOf(e.getValue())));
      result.add(
          new McpServerConfig(
              name,
              transport,
              stringOf(map, "command"),
              argList.stream().map(String::valueOf).toList(),
              envStrings));
    }
    return result;
  }

  /** ${ENV_VAR} 占位解析（001 机制）；未解析到的占位 → 明确报错（该 server 连接失败路径，不静默传字面占位）。 */
  private Map<String, String> resolveEnv(Map<String, String> env, String serverName) {
    Map<String, String> resolved = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, String> e : env.entrySet()) {
      String value = e.getValue();
      if (value != null && value.startsWith("${") && value.endsWith("}")) {
        String varName = value.substring(2, value.length() - 1);
        String actual = System.getenv(varName);
        if (actual == null) {
          throw new IllegalArgumentException(
              "MCP server [" + serverName + "] 环境变量占位未解析: " + varName);
        }
        value = actual;
      }
      resolved.put(e.getKey(), value);
    }
    return Map.copyOf(resolved);
  }

  private String stringOf(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      throw new IllegalArgumentException("mcp_servers.yaml 结构非法: 缺少字段 " + key);
    }
    return value.toString();
  }

  private String readQuietly(Path configFile) {
    try {
      return Files.readString(configFile);
    } catch (Exception e) {
      throw new IllegalArgumentException("mcp_servers.yaml 读取失败: " + configFile, e);
    }
  }
}
