package com.axion.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 目录读写（012-agent-mgmt FR-4）——文件系统真相源 {@code .axion/agents/<name>/} 的唯一写入口。
 *
 * <p>API 上传写完目录后与手工丢目录落得**一模一样**（同一格式、同一位置——这是"两条录入路径殊途同归"的 文件层保证）；归档移入 {@code .axion/archive/}
 * 不物理删（可追溯）；delete 只用于 create 失败回滚 （坑十一）。脚手架模板（R6 口径）：AGENT.md（frontmatter 完整 + 正文骨架）+
 * scripts//skills/ 空目录占位 + REFERENCE.md 字段说明——tools/schedules 留空是最小权限起点。
 *
 * <p>纯类无组件注解（AgentScheduler 先例），装配处显式 @Bean。
 */
public final class AgentStore {

  private static final String AGENT_MD = "AGENT.md";
  private static final String REFERENCE_MD = "REFERENCE.md";

  private final Path workspaceRoot;

  public AgentStore(Path workspaceRoot) {
    this.workspaceRoot = workspaceRoot;
  }

  /** 工作区根（供 WorkspaceApiController 防穿越校验用同一基准）。 */
  public Path workspaceRoot() {
    return workspaceRoot;
  }

  /** {@code .axion/agents/} 根目录。 */
  public Path agentsRoot() {
    return workspaceRoot.resolve("agents");
  }

  /** {@code .axion/archive/} 归档根目录。 */
  public Path archiveRoot() {
    return workspaceRoot.resolve("archive");
  }

  /** 单个 Agent 目录（不保证存在）。 */
  public Path agentDirOf(String name) {
    return agentsRoot().resolve(name);
  }

  /** 写单个 AGENT.md（create 与 update 共用；与手工丢目录格式一模一样）。 */
  public Path write(String name, String agentMarkdown) {
    Path agentDir = agentDirOf(name);
    try {
      Files.createDirectories(agentDir);
      Files.writeString(agentDir.resolve(AGENT_MD), agentMarkdown, StandardCharsets.UTF_8);
      return agentDir;
    } catch (IOException e) {
      throw new IllegalStateException("写 Agent 目录失败: " + agentDir, e);
    }
  }

  /** 写一组文件（5.2.4 保存可能被用户改过的草稿）：相对路径以 {@code agents/<name>/} 为根。 */
  public Path writeAll(String name, Map<String, String> files) {
    Path agentDir = agentDirOf(name);
    try {
      Files.createDirectories(agentDir);
      for (Map.Entry<String, String> entry : files.entrySet()) {
        Path target = agentDir.resolve(entry.getKey()).normalize();
        if (!target.startsWith(agentDir)) {
          throw new IllegalArgumentException("文件相对路径越界: " + entry.getKey());
        }
        Path parent = target.getParent(); // 单次取值入局部变量再判空（011 实录：@Nullable 二次调用判空无效）
        if (parent != null) {
          Files.createDirectories(parent);
        }
        Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
      }
      return agentDir;
    } catch (IOException e) {
      throw new IllegalStateException("写 Agent 文件失败: " + agentDir, e);
    }
  }

  /** 归档：整个 Agent 目录移入 {@code .axion/archive/}（不物理删，可追溯）。 */
  public void archive(String name) {
    Path source = agentDirOf(name);
    if (!Files.isDirectory(source)) {
      throw new IllegalStateException("Agent 目录不存在: " + source);
    }
    Path target = archiveRoot().resolve(name);
    try {
      Files.createDirectories(archiveRoot());
      Files.move(source, target);
    } catch (IOException e) {
      throw new IllegalStateException("归档 Agent 目录失败: " + source, e);
    }
  }

  /** 删除目录（仅 create 失败回滚用，坑十一——不留半个 Agent）。 */
  public void delete(Path agentDir) {
    if (!Files.exists(agentDir)) {
      return;
    }
    try (java.util.stream.Stream<Path> paths = Files.walk(agentDir)) {
      paths.sorted(Comparator.reverseOrder()).forEach(AgentStore::deleteQuietly);
    } catch (IOException e) {
      throw new IllegalStateException("删除 Agent 目录失败: " + agentDir, e);
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      throw new IllegalStateException("删除 Agent 目录失败: " + path, e);
    }
  }

  /** 脚手架模板（R6）：AGENT.md + scripts//skills/ 空目录 + REFERENCE.md——create 的目录内容。 */
  public Map<String, String> scaffold(
      String name, String description, String provider, String model) {
    String providerBlock =
        provider == null || provider.isBlank()
            ? "  name: deepseek" + System.lineSeparator()
            : "  name: " + provider + System.lineSeparator();
    String modelBlock =
        model == null || model.isBlank() ? "" : "  model: " + model + System.lineSeparator();
    String agentMd =
        "---"
            + System.lineSeparator()
            + "name: "
            + name
            + System.lineSeparator()
            + "description: "
            + description
            + System.lineSeparator()
            + "identity:"
            + System.lineSeparator()
            + "  agent_name: "
            + name
            + System.lineSeparator()
            + "  prompt: 你是 "
            + name
            + "，一个由管理台模板脚手架生成的助手。按下方任务步骤工作。"
            + System.lineSeparator()
            + "provider:"
            + System.lineSeparator()
            + providerBlock
            + modelBlock
            + "tools: []"
            + System.lineSeparator()
            + "---"
            + System.lineSeparator()
            + System.lineSeparator()
            + "被触发时，按顺序执行："
            + System.lineSeparator()
            + "1. 理解任务目标。"
            + System.lineSeparator()
            + "2. 使用可用工具完成。"
            + System.lineSeparator()
            + "3. 汇报结果。"
            + System.lineSeparator();
    String referenceMd =
        "# "
            + name
            + " 参考说明"
            + System.lineSeparator()
            + System.lineSeparator()
            + "本文件由管理台模板脚手架生成（012-agent-mgmt）。在此补充字段字典、"
            + "已知可接受差异等参考信息；正文里拿不准时让模型读本文件。"
            + System.lineSeparator();
    Map<String, String> files = new LinkedHashMap<>();
    files.put(AGENT_MD, agentMd);
    files.put("scripts/.keep", "");
    files.put("skills/.keep", "");
    files.put(REFERENCE_MD, referenceMd);
    return files;
  }
}
