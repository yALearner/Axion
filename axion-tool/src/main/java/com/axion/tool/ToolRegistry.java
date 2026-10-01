package com.oryxos.tool;

import com.oryxos.core.OryxTool;
import com.oryxos.core.Profile;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一工具注册表（FR-1）——三种来源的工具（内置、方式二 MCP、方式三 @Tool 包装）统一包装成 {@link OryxTool} 注册进来，ReAct 循环由此对来源无感知（技术方案
 * §6.6）。
 *
 * <p>按 Profile 的 {@code tools} 字段过滤出该 Agent 可用子集，**不多不少**（坑十四：多一个 = 没过滤干净、 少一个 = 过滤过头，都是错）；重名注册明确拒绝
 * + WARN（不静默覆盖——防 MCP 工具遮蔽内置工具）；Profile 声明未注册的工具名明确报错（001 provider 引用校验同款纪律）。
 *
 * <p>消费方：ToolExecutor（按名调度）、{@code oryxos tool list}（003 命令）、第 26 节 {@code /api/v1/tools}、 {@code
 * OryxToolContractTest}（坑十二参数化遍历）。生产 prompt 组装沿用 PromptBuilder 现有路径（core 零改动， 005 S4
 * 实测细化）。纯类交付无组件注解（G4-C1），装配处显式 {@code @Bean}。
 */
public class ToolRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);

  private final Map<String, OryxTool> tools = new ConcurrentHashMap<>();

  /** 三来源统一入口；同名工具不静默覆盖——保留先注册者并 WARN（FR-1）。 */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "工具名为开发/配置可控值（非对话用户输入）；WARN 带名是 FR-1 的明确诊断要求（004 同款口径）")
  public void register(OryxTool tool) {
    OryxTool previous = tools.putIfAbsent(tool.getName(), tool);
    if (previous != null) {
      LOG.warn("工具重名注册被拒绝: {}", tool.getName());
    }
  }

  public boolean contains(String name) {
    return tools.containsKey(name);
  }

  /** 全量列表（防御性拷贝）。 */
  public List<OryxTool> all() {
    return List.copyOf(tools.values());
  }

  /**
   * 按 Profile.tools 精确过滤；声明了未注册的工具名 → 明确报错（不静默少一个）。
   *
   * @throws IllegalArgumentException Profile 声明了未注册的工具名
   */
  public List<OryxTool> filter(Profile profile) {
    List<String> allowed = profile.tools();
    if (allowed == null || allowed.isEmpty()) {
      return List.of();
    }
    List<OryxTool> selected = new ArrayList<>(allowed.size());
    for (String name : allowed) {
      OryxTool tool = tools.get(name);
      if (tool == null) {
        throw new IllegalArgumentException("Profile 声明了未注册的工具: " + name);
      }
      selected.add(tool);
    }
    return List.copyOf(selected);
  }
}
