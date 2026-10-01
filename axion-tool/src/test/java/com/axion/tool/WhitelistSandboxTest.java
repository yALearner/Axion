package com.oryxos.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * WhitelistSandbox 验收 harness（007-sandbox，课件 24 §四）——测的重点不是"放行对不对"，是"绕得过绕不过"。
 *
 * <p>组织：Shell 命令组（FR-4/⑦d）+ 两个最值钱回归（课件 24 §四原文：路径穿越、形似域名）+ 文件路径组（FR-3/⑦a/⑦b， T008）+ HTTP
 * 域名组（FR-5/⑥d，T009）+ 接线回归组（FR-2 空配置全拒/WARN + 枚举四值，T013）。
 */
class WhitelistSandboxTest {

  private WhitelistSandbox shellOnly(String... commands) {
    return new WhitelistSandbox(
        Path.of("."),
        new FileSandboxProperties(List.of()),
        new ShellSandboxProperties(List.of(commands), List.of()),
        new HttpSandboxProperties(List.of()));
  }

  // ---- Shell 命令组（FR-4：首 token 精确匹配；⑦d：环境变量前缀按精确匹配拒） ----

  @Test
  @DisplayName("Shell 组：白名单内首 token 放行（ls）")
  void shellAllowsWhitelistedCommand() {
    WhitelistSandbox sandbox = shellOnly("ls");
    assertThatCode(() -> sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "ls -la")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Shell 组：白名单外首 token 拒绝（rm）")
  void shellRejectsNonWhitelistedCommand() {
    WhitelistSandbox sandbox = shellOnly("ls");
    assertThatThrownBy(
            () -> sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "rm -rf /")))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("命令不在白名单内: rm");
  }

  @Test
  @DisplayName("Shell 组：前导空格 trim 容忍（\"  ls -la\" 放行）")
  void shellToleratesLeadingWhitespace() {
    WhitelistSandbox sandbox = shellOnly("ls");
    assertThatCode(() -> sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "  ls -la")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Shell 组：大小写敏感（LS 拒绝，精确匹配口径）")
  void shellIsCaseSensitive() {
    WhitelistSandbox sandbox = shellOnly("ls");
    assertThatThrownBy(() -> sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "LS")))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("命令不在白名单内: LS");
  }

  @Test
  @DisplayName("Shell 组：环境变量前缀按首 token 精确匹配拒绝（VAR=x ls）")
  void shellRejectsEnvVarPrefix() {
    WhitelistSandbox sandbox = shellOnly("ls");
    assertThatThrownBy(
            () -> sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "VAR=x ls")))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("命令不在白名单内: VAR=x");
  }

  @Test
  @DisplayName("Shell 组：空命令拒绝")
  void shellRejectsBlankCommand() {
    WhitelistSandbox sandbox = shellOnly("ls");
    assertThatThrownBy(() -> sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "   ")))
        .isInstanceOf(SandboxViolationException.class);
  }

  // ---- 两个最值钱回归（课件 24 §四原文，实现之前写——直接决定 matchesDomain 怎么写） ----

  @Test
  @DisplayName("最值钱回归之一：相对路径穿越必须被拦（normalize 吸收 ../）")
  void pathTraversalMustBeBlocked() {
    // 白名单只有 /workspace，构造 .. 序列爬到白名单之外
    WhitelistSandbox sandbox =
        new WhitelistSandbox(
            Path.of("."),
            new FileSandboxProperties(List.of("/workspace")),
            new ShellSandboxProperties(List.of(), List.of()),
            new HttpSandboxProperties(List.of()));
    assertThatThrownBy(
            () ->
                sandbox.enforce(
                    new SandboxAction(ActionType.FILE_READ, "/workspace/../../outside/secret.txt")))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("路径不在白名单内");
  }

  @Test
  @DisplayName("最值钱回归之二：通配符域名不能被形似域名绕过（点号边界）")
  void wildcardDomainMustNotBeBypassed() {
    // 白名单：*.example.com
    WhitelistSandbox sandbox =
        new WhitelistSandbox(
            Path.of("."),
            new FileSandboxProperties(List.of()),
            new ShellSandboxProperties(List.of(), List.of()),
            new HttpSandboxProperties(List.of("*.example.com")));
    assertThatCode(
            () ->
                sandbox.enforce(
                    new SandboxAction(ActionType.HTTP_REQUEST, "https://api.example.com/x")))
        .doesNotThrowAnyException();
    // evil-example.com 以 "example.com" 结尾但不是子域！
    assertThatThrownBy(
            () ->
                sandbox.enforce(
                    new SandboxAction(ActionType.HTTP_REQUEST, "https://evil-example.com/x")))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("域名不在白名单内");
    // 平台注记：Windows 上 "/workspace" 解析为当前盘根（\workspace），穿越用例断言结果不变（仍拒绝）
  }

  // ---- 文件路径组（FR-3：normalize + 前缀匹配；⑦a 相对根；⑦b Windows 大小写） ----

  private WhitelistSandbox filesOnly(String... roots) {
    return new WhitelistSandbox(
        Path.of("."),
        new FileSandboxProperties(List.of(roots)),
        new ShellSandboxProperties(List.of(), List.of()),
        new HttpSandboxProperties(List.of()));
  }

  @Test
  @DisplayName("文件组：白名单内路径放行")
  void fileAllowsPathUnderRoot() {
    WhitelistSandbox sandbox = filesOnly("/workspace");
    assertThatCode(
            () ->
                sandbox.enforce(
                    new SandboxAction(ActionType.FILE_READ, "/workspace/reports/x.txt")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("文件组：白名单外路径拒绝")
  void fileRejectsPathOutsideRoot() {
    WhitelistSandbox sandbox = filesOnly("/workspace");
    assertThatThrownBy(
            () -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, "/etc/passwd")))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("路径不在白名单内");
  }

  @Test
  @DisplayName("⑦a 回归钉：相对根 + 绝对 target 放行（根构造期 toAbsolutePath 后命中）")
  void relativeRootMatchesAbsoluteTarget() {
    WhitelistSandbox sandbox = filesOnly(".oryxos/workspace");
    String absoluteTarget = Path.of(".oryxos/workspace", "x.txt").toAbsolutePath().toString();
    assertThatCode(() -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, absoluteTarget)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("文件组：白名单根构造期 normalize（含 .. 的根归一后命中）")
  void rootIsNormalizedAtConstruction() {
    WhitelistSandbox sandbox = filesOnly("/workspace/sub/..");
    // 构造期归一为 /workspace 后命中；不归一则前缀 "/workspace/sub/.." 永不匹配
    assertThatCode(
            () ->
                sandbox.enforce(
                    new SandboxAction(ActionType.FILE_READ, "/workspace/reports/x.txt")))
        .doesNotThrowAnyException();
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  @DisplayName("⑦b 平台条件：Windows 大小写变体放行（lower-case 归一，Linux 维持大小写敏感）")
  void windowsCaseVariantAllowed() {
    String root = Path.of("/Workspace").toAbsolutePath().toString();
    String target = Path.of("/workspace/inside/file.txt").toAbsolutePath().toString();
    WhitelistSandbox sandbox = filesOnly(root);
    assertThatCode(() -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, target)))
        .doesNotThrowAnyException();
  }

  // ---- HTTP 域名组（FR-5：精确匹配 + 点号边界通配；⑥d host null 拒绝） ----

  private WhitelistSandbox httpOnly(String... domains) {
    return new WhitelistSandbox(
        Path.of("."),
        new FileSandboxProperties(List.of()),
        new ShellSandboxProperties(List.of(), List.of()),
        new HttpSandboxProperties(List.of(domains)));
  }

  @Test
  @DisplayName("HTTP 组：精确匹配放行（wttr.in）")
  void httpAllowsExactDomain() {
    WhitelistSandbox sandbox = httpOnly("wttr.in");
    assertThatCode(
            () ->
                sandbox.enforce(
                    new SandboxAction(ActionType.HTTP_REQUEST, "https://wttr.in/Shanghai")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("HTTP 组：白名单外域名拒绝")
  void httpRejectsUnknownDomain() {
    WhitelistSandbox sandbox = httpOnly("wttr.in");
    assertThatThrownBy(
            () -> sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://evil.com/x")))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("域名不在白名单内");
  }

  @Test
  @DisplayName("⑥d：host 解析不到拒绝（可解析但无 host 的 URI 不 NPE 不漏放）")
  void httpRejectsUnresolvableHost() {
    WhitelistSandbox sandbox = httpOnly("wttr.in");
    // "not-a-url"（无 scheme 语义的可解析 URI，getHost()==null）与 file: 这类无 authority 的 URI 都走 null→拒绝
    assertThatThrownBy(
            () -> sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "not-a-url")))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("域名不在白名单内");
  }

  @Test
  @DisplayName("⑥d：无 authority URI 拒绝（file:/etc/passwd 借 HTTP 工具读本地文件被拦）")
  void httpRejectsUriWithoutAuthority() {
    WhitelistSandbox sandbox = httpOnly("wttr.in");
    assertThatThrownBy(
            () -> sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "file:/etc/passwd")))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("域名不在白名单内");
  }

  // ---- 接线回归组（FR-2 空配置 = 全拒绝 fail-closed + 构造期 WARN；FR-1 枚举四值全覆盖） ----

  @Test
  @DisplayName("空配置 = 全拒绝：三块全空时三类 action 全部抛异常（「不校验」口径的回归钉）")
  void emptyConfigRejectsAll() {
    WhitelistSandbox sandbox =
        new WhitelistSandbox(
            Path.of("."),
            new FileSandboxProperties(List.of()),
            new ShellSandboxProperties(List.of(), List.of()),
            new HttpSandboxProperties(List.of()));

    assertThatThrownBy(() -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, "/x")))
        .isInstanceOf(SandboxViolationException.class);
    assertThatThrownBy(() -> sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "ls")))
        .isInstanceOf(SandboxViolationException.class);
    assertThatThrownBy(
            () -> sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://wttr.in")))
        .isInstanceOf(SandboxViolationException.class);
  }

  @Test
  @DisplayName("⑦c：任一白名单为空构造期 WARN（ListAppender 捕获，消息只含配置键名）")
  void emptyConfigWarnsAtConstruction() {
    Logger logger = (Logger) LoggerFactory.getLogger(WhitelistSandbox.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      new WhitelistSandbox(
          Path.of("."),
          new FileSandboxProperties(List.of()),
          new ShellSandboxProperties(List.of(), List.of()),
          new HttpSandboxProperties(List.of()));
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("file.allowed_paths");
            })
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("shell.allowed_commands");
            })
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("http.allowed_domains");
            });
  }

  @Test
  @DisplayName("FR-1 枚举四值全覆盖：FILE_READ/FILE_WRITE 同路由到 checkFilePath（读写共用同一份路径白名单）")
  void allFourActionTypesRouted() {
    WhitelistSandbox sandbox =
        new WhitelistSandbox(
            Path.of("."),
            new FileSandboxProperties(List.of("/workspace")),
            new ShellSandboxProperties(List.of("ls"), List.of()),
            new HttpSandboxProperties(List.of("wttr.in")));

    // FILE_READ 与 FILE_WRITE 同路由：两者都经 checkFilePath 前缀匹配
    assertThatCode(
            () -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, "/workspace/a.txt")))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, "/workspace/b.txt")))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, "/outside/b.txt")))
        .isInstanceOf(SandboxViolationException.class);
    assertThatCode(() -> sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "ls")))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://wttr.in/x")))
        .doesNotThrowAnyException();
  }

  // ---- 011-plugin-agent（FR-5，③ 拍板）：解释器双白名单与子集 WARN ----

  @Test
  @DisplayName("双白名单：解释器命令须首 token 同时命中 allowed_commands 与 allowed_interpreters（只命中其一不按 L3 处理）")
  void interpreterRequiresBothWhitelists(@TempDir Path workspace) throws Exception {
    Path agentsRoot = Files.createDirectories(workspace.resolve("agents"));
    Path agentDir = Files.createDirectories(agentsRoot.resolve("ops-agent"));
    Files.writeString(agentDir.resolve("AGENT.md"), "---\nname: ops-agent\n---\n");

    // 只命中 commands（不在 interpreters）：非解释器语义——首 token 白名单即全部校验，不要求 Agent 上下文
    WhitelistSandbox commandOnly =
        new WhitelistSandbox(
            workspace,
            new FileSandboxProperties(List.of()),
            new ShellSandboxProperties(List.of("python"), List.of()),
            new HttpSandboxProperties(List.of()));
    assertThatCode(
            () ->
                commandOnly.enforce(
                    new SandboxAction(ActionType.SHELL_COMMAND, "python /etc/x.py")))
        .doesNotThrowAnyException();

    // 双命中：L3 校验生效——无 Agent 上下文一律拒绝（⑥ fail-closed）
    WhitelistSandbox both =
        new WhitelistSandbox(
            workspace,
            new FileSandboxProperties(List.of()),
            new ShellSandboxProperties(List.of("python"), List.of("python")),
            new HttpSandboxProperties(List.of()));
    assertThatThrownBy(
            () -> both.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "python scripts/x.py")))
        .isInstanceOf(SandboxViolationException.class)
        .hasMessageContaining("Agent 上下文");
  }

  @Test
  @DisplayName("③ 拍板子集 WARN：allowed_interpreters 含未进 allowed_commands 的项 → 构造期 WARN（消息只含配置键名）")
  void interpreterSubsetViolationWarns() {
    Logger logger = (Logger) LoggerFactory.getLogger(WhitelistSandbox.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      new WhitelistSandbox(
          Path.of("."),
          new FileSandboxProperties(List.of()),
          new ShellSandboxProperties(List.of("ls"), List.of("python")),
          new HttpSandboxProperties(List.of()));
    } finally {
      logger.detachAppender(appender);
    }
    assertThat(appender.list)
        .anyMatch(
            event ->
                event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("allowed_interpreters")
                    && event.getFormattedMessage().contains("allowed_commands"));
  }
}
