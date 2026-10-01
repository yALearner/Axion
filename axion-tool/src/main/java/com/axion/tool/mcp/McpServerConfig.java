package com.oryxos.tool.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP server 配置行（`.oryxos/mcp_servers.yaml` 解析产物，FR-5）——name/transport/command/args/env； 不可变 record
 * + 防御性拷贝。核心阶段仅 stdio transport（SSE 放扩展，编程指南 §4.4）；env 值支持 {@code ${ENV_VAR}} 占位（001 机制），凭证不明文。
 *
 * @param name server 注册名（WARN 日志带此名）
 * @param transport 传输方式（核心阶段仅 "stdio"）
 * @param command 启动命令（如 npx）
 * @param args 命令参数
 * @param env 环境变量（值可含 ${ENV_VAR} 占位，解析在 McpClientService）
 */
public record McpServerConfig(
    String name, String transport, String command, List<String> args, Map<String, String> env) {

  public McpServerConfig {
    args = args == null ? List.of() : List.copyOf(args);
    env = env == null ? Map.of() : Map.copyOf(env);
  }
}
