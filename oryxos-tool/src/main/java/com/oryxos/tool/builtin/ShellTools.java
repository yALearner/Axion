package com.oryxos.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.oryxos.core.JsonSchema;
import com.oryxos.core.OryxTool;
import com.oryxos.core.Profile;
import com.oryxos.core.ProfileContext;
import com.oryxos.core.ToolResult;
import com.oryxos.tool.ActionType;
import com.oryxos.tool.Sandbox;
import com.oryxos.tool.SandboxAction;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 内置 Tool {@code shell}（FR-3）：执行 bash 命令——execute 首行 {@code sandbox.enforce(SHELL_COMMAND,
 * command)} 先于执行（坑十）；带超时（构造注入 timeoutMs、装配处默认 30_000——G4-C1 可测性），超时强制销毁进程 + 明确报错； 退出码非 0 →
 * failure（stdout/stderr 合并进 errorMessage）。命令白名单规则本体归 23/24 节。
 *
 * <p>011-plugin-agent FR-5（坑六）：构造注入解释器集合 + workspaceRoot——首 token ∈ 解释器集合且处于 Agent 处理上下文时， 子进程 cwd
 * 设为当前 Agent 目录（{@code workspaceRoot/agents/<name>}，经 ProfileContext）——相对路径按"这个 Agent 的目录"解析， 不设则
 * {@code python scripts/foo.py} 按进程启动目录解析，要么找不到、要么跑到别的目录的同名脚本；非解释器命令（ls/cat 等） cwd 行为不变（007 回归）。
 *
 * <p>平台假设：生产 Linux（bash 在 PATH）；Windows 开发机按「PATH 探测 → {@code where git} 推导 Git 根目录 → 标准安装路径」 解析
 * Git Bash 的 bash（PowerShell 终端 bash 不在 PATH 的实测修复，2026-09-05）；全部解析失败时保持 "bash" 并由 ProcessBuilder
 * 给出清晰报错。纯类交付无组件注解（G4-C1）。审计复用 ToolExecutor 既有路径。
 */
public class ShellTools implements OryxTool {

  private static final List<String> STANDARD_GIT_BASH =
      List.of(
          "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
          "C:\\Program Files\\Git\\bin\\bash.exe",
          "C:\\Program Files (x86)\\Git\\usr\\bin\\bash.exe");

  private final Sandbox sandbox;
  private final long timeoutMs;
  private final String shell;
  private final Set<String> interpreters;
  private final Path workspaceRoot;

  public ShellTools(Sandbox sandbox, long timeoutMs, Set<String> interpreters, Path workspaceRoot) {
    this.sandbox = sandbox;
    this.timeoutMs = timeoutMs;
    this.shell = resolveShell();
    this.interpreters = Set.copyOf(interpreters);
    this.workspaceRoot = workspaceRoot.normalize().toAbsolutePath();
  }

  @Override
  public String getName() {
    return "shell";
  }

  @Override
  public String getDescription() {
    return "执行一条 bash 命令并返回标准输出。command 为命令（必填）；超时或退出码非 0 时明确报错。";
  }

  @Override
  public JsonSchema getInputSchema() {
    return new JsonSchema(
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("command", Map.of("type", "string", "description", "要执行的 bash 命令")),
            "required",
            List.of("command")));
  }

  @Override
  @SuppressFBWarnings(
      value = "COMMAND_INJECTION",
      justification =
          "shell 工具的本职即执行 LLM 给出的命令；命令白名单由 execute 首行 Sandbox.enforce 先行校验"
              + "（23/24 节实现）——核心阶段既定信任边界（CLAUDE.md 信任边界口径，坑十）")
  public ToolResult execute(JsonNode input) {
    String command = input.get("command").asText();
    sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, command)); // 坑十：enforce 先于执行
    Process process;
    try {
      ProcessBuilder pb = new ProcessBuilder(shell, "-c", command).redirectErrorStream(true);
      enrichGitBashPath(pb);
      bindAgentWorkingDir(pb, command); // 坑六：解释器命令 cwd = 当前 Agent 目录
      process = pb.start();
    } catch (IOException e) {
      return ToolResult.failure("启动命令失败: " + e.getMessage(), false);
    }
    try {
      boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        return ToolResult.failure("命令执行超时（" + timeoutMs + "ms），已强制终止: " + command, false);
      }
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        return ToolResult.failure("命令退出码 " + process.exitValue() + ": " + output, false);
      }
      return ToolResult.success(output);
    } catch (IOException e) {
      return ToolResult.failure("读取命令输出失败: " + e.getMessage(), false);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      return ToolResult.failure("命令执行被中断", false);
    }
  }

  /**
   * 坑六（011 FR-5）：解释器命令且处于 Agent 处理上下文时，cwd 设为当前 Agent 目录——相对路径按"这个 Agent 的目录"解析。 非解释器命令（ls/cat
   * 等）cwd 行为不变（007 回归）；无 Agent 上下文时解释器命令已在沙箱被拒（⑥ fail-closed），此处不设。
   */
  private void bindAgentWorkingDir(ProcessBuilder pb, String command) {
    String firstToken = command.trim().split("\\s+", 2)[0];
    if (!interpreters.contains(firstToken)) {
      return;
    }
    Profile profile = ProfileContext.current();
    if (profile == null) {
      return;
    }
    Path agentDir =
        workspaceRoot.resolve("agents").resolve(profile.name()).normalize().toAbsolutePath();
    pb.directory(agentDir.toFile());
  }

  /**
   * 绝对路径 Git Bash 场景（PowerShell 终端解析到 usr\bin\bash.exe 时）：子进程继承的 PATH 里没有 Git 的
   * usr/bin——coreutils（sleep/ls 等）不可见。把 Git 根目录的 usr/bin、bin 前置到子进程 PATH。 生产 Linux（shell="bash"
   * 非绝对路径）不触发。
   */
  private void enrichGitBashPath(ProcessBuilder pb) {
    Path shellPath = Path.of(shell);
    if (!shellPath.isAbsolute()) {
      return;
    }
    Path binDir = shellPath.getParent();
    Path gitRoot = binDir == null ? null : binDir.getParent();
    if (gitRoot == null) {
      return;
    }
    String extra = gitRoot + "\\usr\\bin;" + gitRoot + "\\bin;";
    pb.environment().merge("PATH", extra, (oldValue, ignored) -> extra + oldValue);
  }

  /** 解析 bash 可执行文件：PATH 探测 → where git 推导 → 标准安装路径 → "bash" 兜底（报错由 ProcessBuilder 清晰给出）。 */
  private static String resolveShell() {
    if (canLaunch("bash")) {
      return "bash";
    }
    String gitRoot = gitRootFromWhereGit();
    if (gitRoot != null) {
      String usrBash = gitRoot + "\\usr\\bin\\bash.exe";
      if (canLaunch(usrBash)) {
        return usrBash;
      }
      String binBash = gitRoot + "\\bin\\bash.exe";
      if (canLaunch(binBash)) {
        return binBash;
      }
    }
    for (String candidate : STANDARD_GIT_BASH) {
      if (canLaunch(candidate)) {
        return candidate;
      }
    }
    return "bash";
  }

  @SuppressFBWarnings(
      value = "COMMAND_INJECTION",
      justification =
          "解析器仅探测固定候选可执行文件与 cmd 内建 where——命令串不含任何用户输入（用户命令的注入防护见 execute 的"
              + " Sandbox.enforce 先行校验，坑十）")
  private static boolean canLaunch(String executable) {
    try {
      new ProcessBuilder(executable, "--version").start().destroy();
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /** Windows：`cmd /c where git` 输出第一行（如 D:\git\Git\cmd\git.exe）→ 取 Git 根目录；非 Windows 返回 null。 */
  private static String gitRootFromWhereGit() {
    try {
      Process where = new ProcessBuilder("cmd", "/c", "where", "git").start();
      String output =
          new String(where.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      where.destroy();
      if (output.isEmpty()) {
        return null;
      }
      Path gitExe = Path.of(output.split("\\R", 2)[0]);
      Path binOrCmdDir = gitExe.getParent(); // ...\cmd 或 ...\bin
      if (binOrCmdDir == null) {
        return null;
      }
      Path root = binOrCmdDir.getParent();
      return root == null ? null : root.toString();
    } catch (IOException e) {
      return null; // 非 Windows 或 where 不可用——Linux 上 bash 已在 PATH，走兜底即可
    }
  }
}
