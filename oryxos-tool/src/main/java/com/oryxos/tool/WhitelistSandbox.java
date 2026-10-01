package com.oryxos.tool;

import com.oryxos.core.Profile;
import com.oryxos.core.ProfileContext;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 核心阶段唯一 Sandbox 实现：三层白名单校验（007-sandbox FR-1，课件 24 §3.2）+ 011-plugin-agent 演进（FR-5/FR-6）。
 *
 * <p>接口墙（{@link Sandbox#enforce}，宪法 VI）后面的第一档实现——按 {@code ActionType} 路由三个**私有**校验方法 （外部只看得到 {@code
 * enforce} 一个入口，避免接口被本档实现带偏）；任意校验失败抛 {@link SandboxViolationException}， 复用 ToolExecutor 既有审计路径落
 * {@code tool_invocations}（success=false + error_message，零新增审计）。
 *
 * <p>口径（007-sandbox 修订说明 ⑦）：
 *
 * <ul>
 *   <li>⑦a 白名单根构造期 {@code normalize().toAbsolutePath()}——相对根按启动目录解析；根不绝对化则相对根与绝对 target
 *       永不匹配、文件类工具全拒
 *   <li>⑦b Windows 下 root/target lower-case 归一后比较（{@code Path.startsWith} 大小写敏感而 NTFS 不敏感，归一
 *       不扩大放行面）；Linux 维持大小写敏感
 *   <li>⑦c 任一白名单为空构造期 WARN（启动诊断——消息只含配置键名，不违反 NFR-3 用户可控值不进日志口径）
 *   <li>⑥d host 解析不到（null）→ 拒绝（课件骨架无此防御，会 NPE——实现级明确新增）
 * </ul>
 *
 * <p>011-plugin-agent 演进：FILE_READ 动态白名单 = 静态白名单 ∪ 当前 Agent 目录（坑七：渐进式披露断链防线，未绑定实体路径在 {@code
 * .oryxos/skills/} 下、不在 Agent 目录下，天然不可读）；解释器命令 L3 校验——双白名单命中后按简单形态钉死（坑九： "解释器 + 单个 scripts/
 * 下相对路径参数（+ 可选脚本参数）"，链式/引号/元字符/脚本路径前选项一律拒绝；无 Agent 上下文 fail-closed， 修订说明 ⑥）。FILE_WRITE
 * 不动（最小权限：Agent 不需要写自己的目录）。
 *
 * <p>诚实标注：白名单是"劝阻级"防线，防的是模型犯傻误操作，防不住蓄意绕过（软链逃逸/TOCTOU 等不覆盖）。构造期一次性
 * 拷贝三份不可变集合——无共享可变状态，虚拟线程并发下线程安全；配置不可热更新，改白名单须重启。
 */
public class WhitelistSandbox implements Sandbox {

  private static final Logger LOG = LoggerFactory.getLogger(WhitelistSandbox.class);

  private static final boolean WINDOWS =
      System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

  private final Path
      workspaceRoot; // 011：Agent 目录派生根（ProfileContext + name → workspaceRoot/agents/<name>）
  private final List<Path> allowedRoots; // 构造期 normalize + toAbsolutePath（相对根按启动目录解析，⑦a）
  private final Set<String> allowedCommands;
  private final Set<String> allowedInterpreters; // ③ 拍板：解释器集合（须为 allowedCommands 子集才生效）
  private final List<String> allowedDomainPatterns;

  public WhitelistSandbox(
      Path workspaceRoot,
      FileSandboxProperties fileProps,
      ShellSandboxProperties shellProps,
      HttpSandboxProperties httpProps) {
    this.workspaceRoot = workspaceRoot.normalize().toAbsolutePath();
    this.allowedRoots =
        fileProps.allowedPaths().stream()
            .map(Path::of)
            .map(Path::normalize)
            .map(Path::toAbsolutePath)
            .toList();
    this.allowedCommands = Set.copyOf(shellProps.allowedCommands());
    this.allowedInterpreters = Set.copyOf(shellProps.allowedInterpreters());
    this.allowedDomainPatterns = List.copyOf(httpProps.allowedDomains());
    warnIfEmpty();
  }

  private void warnIfEmpty() {
    // 启动诊断：消息只含配置键名（非用户可控值），违规原因仍只进审计不进日志（NFR-3，⑦c）
    if (allowedRoots.isEmpty()) {
      LOG.warn("Sandbox 白名单 file.allowed_paths 为空——文件读写将全部被拒（空 = 什么都不允许，不是不校验）");
    }
    if (allowedCommands.isEmpty()) {
      LOG.warn("Sandbox 白名单 shell.allowed_commands 为空——shell 命令将全部被拒（空 = 什么都不允许，不是不校验）");
    }
    if (allowedDomainPatterns.isEmpty()) {
      LOG.warn("Sandbox 白名单 http.allowed_domains 为空——HTTP 请求将全部被拒（空 = 什么都不允许，不是不校验）");
    }
    // ③ 拍板子集校验：声明了但未进 commands 的解释器永不命中——构造期 WARN（消息只含配置键名，007 ⑦c 同款诊断纪律）
    boolean neverMatch =
        allowedInterpreters.stream()
            .anyMatch(interpreter -> !allowedCommands.contains(interpreter));
    if (neverMatch) {
      LOG.warn(
          "Sandbox 配置 shell.allowed_interpreters 含未进 shell.allowed_commands 白名单的解释器——永不命中（子集校验）");
    }
  }

  @Override
  public void enforce(SandboxAction action) {
    // 无 default：新增 ActionType 值时编译期强制处理（枚举四值全覆盖）
    switch (action.type()) {
      case FILE_READ -> checkFileRead(action.target());
      case FILE_WRITE -> checkFilePath(action.target());
      case SHELL_COMMAND -> checkShellCommand(action.target());
      case HTTP_REQUEST -> checkHttpUrl(action.target());
    }
  }

  /** 坑七（011 FR-6）：FILE_READ = 静态白名单 ∪ 当前 Agent 目录（无上下文静态兜底）——自己 REFERENCE.md/绑定 SKILL.md 可读。 */
  private void checkFileRead(String rawPath) {
    Path target = Path.of(rawPath).normalize().toAbsolutePath();
    Path agentDir = currentAgentDir();
    boolean allowed =
        allowedRoots.stream().anyMatch(root -> startsWithRoot(target, root))
            || (agentDir != null && startsWithRoot(target, agentDir));
    if (!allowed) {
      throw new SandboxViolationException("路径不在白名单内: " + rawPath);
    }
  }

  private void checkFilePath(String rawPath) {
    Path target = Path.of(rawPath).normalize().toAbsolutePath();
    if (allowedRoots.stream().noneMatch(root -> startsWithRoot(target, root))) {
      throw new SandboxViolationException("路径不在白名单内: " + rawPath);
    }
  }

  private void checkShellCommand(String command) {
    String trimmed = command.trim();
    // limit=2：只取首 token，语义与课件骨架 split("\\s+")[0] 一致（ErrorProne StringSplitter 门禁要求显式 limit）
    String firstToken = trimmed.split("\\s+", 2)[0];
    if (!allowedCommands.contains(firstToken)) {
      throw new SandboxViolationException("命令不在白名单内: " + firstToken);
    }
    if (allowedInterpreters.contains(firstToken)) {
      enforceInterpreterCommand(trimmed, firstToken);
    }
    // 非解释器命令：007 语义原样（首 token 白名单即全部校验）
  }

  /**
   * L3 脚本校验（011 FR-5，data-model §四 状态机 3~7 步）：解释器命令只接受简单形态——"解释器 + 单个 scripts/ 下相对路径参数 （+
   * 可选脚本参数，token[2..] 透传，S2 澄清）"；链式/引号/元字符/脚本路径前选项/裸解释器一律拒绝（坑九：首 token 校验挡不住 {@code python
   * scripts/x.py ; curl ...} 链式穿透）；无 Agent 上下文一律拒绝（修订说明 ⑥ fail-closed）。
   */
  private void enforceInterpreterCommand(String command, String interpreter) {
    Profile profile = ProfileContext.current();
    if (profile == null) {
      // ⑥：工具执行只发生在 AgentService.process 内（ProfileContext 必被设置，002 坑四钉死）——缺失意味着绕开处理流程
      throw new SandboxViolationException("解释器命令需要 Agent 上下文: " + interpreter);
    }
    // 坑九：引号/元字符全命令拒绝——链式/命令替换/glob 一律不放行（简单形态）
    for (int i = 0; i < command.length(); i++) {
      char c = command.charAt(i);
      if (c == '\'' || c == '"' || c == ';' || c == '|' || c == '&' || c == '>' || c == '<'
          || c == '`' || c == '$' || c == '(' || c == ')' || c == '*' || c == '?' || c == '['
          || c == ']' || c == '\n' || c == '\r') {
        throw new SandboxViolationException("解释器命令含引号或 shell 元字符，仅接受简单形态: " + interpreter);
      }
    }
    String[] tokens = command.split("\\s+", -1);
    if (tokens.length < 2) {
      throw new SandboxViolationException("解释器命令缺少脚本路径（仅接受简单形态）: " + interpreter);
    }
    String scriptToken = tokens[1];
    if (scriptToken.startsWith("-")) {
      // 解释器级选项（-c/-u 等）一律拒绝——只接受"解释器 + 脚本路径（+ 脚本参数）"形态
      throw new SandboxViolationException("解释器级选项不接受（仅接受简单形态）: " + scriptToken);
    }
    Path agentDir = currentAgentDir();
    Path scriptPath = agentDir.resolve(scriptToken).normalize().toAbsolutePath();
    Path scriptsRoot = agentDir.resolve("scripts").normalize().toAbsolutePath();
    if (!startsWithRoot(scriptPath, scriptsRoot)) {
      throw new SandboxViolationException("脚本路径不在本 Agent scripts/ 目录内: " + scriptToken);
    }
    // token[2..] 脚本参数透传（S2 澄清）：已过全命令元字符检查，直接放行
  }

  private void checkHttpUrl(String url) {
    String host = URI.create(url).getHost();
    // ⑥d：解析不到（null）→ 拒绝——畸形 URL 不因 NPE 漏放（课件骨架无此防御）
    boolean allowed =
        host != null
            && allowedDomainPatterns.stream().anyMatch(pattern -> matchesDomain(host, pattern));
    if (!allowed) {
      throw new SandboxViolationException("域名不在白名单内: " + host);
    }
  }

  /** 当前 Agent 目录（ProfileContext 提供；不在处理流程内返回 null——FILE_READ 静态兜底、解释器命令已先拒）。 */
  private Path currentAgentDir() {
    Profile profile = ProfileContext.current();
    if (profile == null) {
      return null;
    }
    return workspaceRoot.resolve("agents").resolve(profile.name()).normalize().toAbsolutePath();
  }

  private boolean startsWithRoot(Path target, Path root) {
    if (!WINDOWS) {
      return target.startsWith(root);
    }
    // ⑦b：startsWith 大小写敏感而 NTFS 不敏感——lower-case 归一后按元素比较（Path.startsWith 逐段语义，
    // 不可退化为字符串前缀匹配——"d:\workspace2" 不得命中根 "d:\workspace"）
    Path loweredTarget = Path.of(target.toString().toLowerCase(Locale.ROOT));
    Path loweredRoot = Path.of(root.toString().toLowerCase(Locale.ROOT));
    return loweredTarget.startsWith(loweredRoot);
  }

  private boolean matchesDomain(String host, String pattern) {
    if (pattern.startsWith("*.")) {
      return host.endsWith(pattern.substring(1)); // ".example.com" 带点号边界——evil-example.com 不命中
    }
    return host.equals(pattern);
  }
}
