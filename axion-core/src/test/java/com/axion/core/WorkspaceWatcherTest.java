package com.axion.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WorkspaceWatcher harness（012 FR-1/FR-5）——丢目录触发同一段 register(agentDir)（坑十七）、删目录触发注销、 坏目录 WARN
 * 跳过不拖垮监听、启动全量扫拾取存量。
 */
class WorkspaceWatcherTest {

  private AgentLifecycleService lifecycleStub(ProfileRegistry registry, AgentLoader loader) {
    return new AgentLifecycleService(
        loader,
        registry,
        mock(AgentScheduler.class),
        mock(AgentStore.class),
        mock(LlmGateway.class),
        p -> {},
        Set.of("deepseek"),
        "deepseek",
        "deepseek-chat");
  }

  private Path agentDir(Path workspace, String name) {
    return workspace.resolve("agents").resolve(name);
  }

  @Test
  @DisplayName("启动全量扫：存量合法目录逐目录注册、坏目录跳过不拖垮（坑十七：与 API 同一段注册代码）")
  void fullScanRegistersExistingAndSkipsBroken(@TempDir Path workspace) throws Exception {
    Files.createDirectories(agentDir(workspace, "weather"));
    Files.writeString(
        agentDir(workspace, "weather").resolve("AGENT.md"),
        "---\nname: weather\nprovider:\n  name: deepseek\n---\n正文");
    Files.createDirectories(agentDir(workspace, "broken"));
    Files.writeString(agentDir(workspace, "broken").resolve("AGENT.md"), "这不是 YAML frontmatter");

    ProfileRegistry registry = new ProfileRegistry();
    AgentLoader loader = new AgentLoader();
    WorkspaceWatcher watcher = new WorkspaceWatcher(workspace, lifecycleStub(registry, loader));

    watcher.scanOnce(); // 启动全量扫

    assertThat(registry.exists("weather")).isTrue(); // 合法目录已注册
    assertThat(registry.exists("broken")).isFalse(); // 坏目录跳过（WARN 不阻断）
  }

  @Test
  @DisplayName("实时监听：手工丢目录 → 监听事件触发 register(agentDir)（免重启即上线）")
  void watcherPicksUpDroppedDirectory(@TempDir Path workspace) throws Exception {
    Files.createDirectories(workspace.resolve("agents"));
    ProfileRegistry registry = new ProfileRegistry();
    AgentLoader loader = new AgentLoader();
    WorkspaceWatcher watcher = new WorkspaceWatcher(workspace, lifecycleStub(registry, loader));

    Thread thread = new Thread(watcher::run, "axion-workspace-watcher-test");
    thread.setDaemon(true);
    thread.start();
    try {
      waitFor(watcher::isWatching, 5000); // 等监听注册就绪再变更（防启动竞态）
      dropCompleteDir(workspace, "weather-daily"); // 原子 move 整目录（scp/git 落盘形态）

      waitFor(() -> registry.exists("weather-daily"), 5000);
      assertThat(registry.exists("weather-daily")).isTrue(); // 免重启即上线（坑十七）
    } finally {
      watcher.stop();
      thread.join(5000);
    }
  }

  @Test
  @DisplayName("实时监听：删目录 → 注销（与 API 删除同一段注销代码，坑十七对称）")
  void watcherUnregistersOnDirectoryDelete(@TempDir Path workspace) throws Exception {
    Files.createDirectories(agentDir(workspace, "weather-daily"));
    Files.writeString(
        agentDir(workspace, "weather-daily").resolve("AGENT.md"),
        "---\nname: weather-daily\nprovider:\n  name: deepseek\n---\n每日天气");
    ProfileRegistry registry = new ProfileRegistry();
    AgentLoader loader = new AgentLoader();
    WorkspaceWatcher watcher = new WorkspaceWatcher(workspace, lifecycleStub(registry, loader));
    watcher.scanOnce();
    assertThat(registry.exists("weather-daily")).isTrue();

    Thread thread = new Thread(watcher::run, "axion-workspace-watcher-test");
    thread.setDaemon(true);
    thread.start();
    try {
      waitFor(watcher::isWatching, 5000); // 等监听注册就绪再变更（防启动竞态）
      deleteRecursively(agentDir(workspace, "weather-daily"));
      waitFor(() -> !registry.exists("weather-daily"), 5000);
      assertThat(registry.exists("weather-daily")).isFalse(); // 目录删除 → 注销
    } finally {
      watcher.stop();
      thread.join(5000);
    }
  }

  @Test
  @DisplayName("坏目录不拖垮监听：后续合法目录仍被拾取")
  void brokenDirectoryDoesNotKillWatcher(@TempDir Path workspace) throws Exception {
    Files.createDirectories(workspace.resolve("agents"));
    ProfileRegistry registry = new ProfileRegistry();
    AgentLoader loader = new AgentLoader();
    WorkspaceWatcher watcher = new WorkspaceWatcher(workspace, lifecycleStub(registry, loader));

    Thread thread = new Thread(watcher::run, "axion-workspace-watcher-test");
    thread.setDaemon(true);
    thread.start();
    try {
      waitFor(watcher::isWatching, 5000); // 等监听注册就绪再变更（防启动竞态）
      Path stagingBroken = workspace.resolve("staging-broken").resolve("broken");
      Files.createDirectories(stagingBroken);
      Files.writeString(stagingBroken.resolve("AGENT.md"), "坏内容");
      Files.move(stagingBroken, agentDir(workspace, "broken"));
      dropCompleteDir(workspace, "later-good");

      waitFor(() -> registry.exists("later-good"), 5000);
      assertThat(registry.exists("later-good")).isTrue(); // 坏目录跳过、监听仍在
      assertThat(registry.exists("broken")).isFalse();
    } finally {
      watcher.stop();
      thread.join(5000);
    }
  }

  @Test
  @DisplayName("无 agents 目录：全量扫零注册（未初始化的合法空态）")
  void scanWithoutAgentsDirRegistersNothing(@TempDir Path workspace) {
    ProfileRegistry registry = new ProfileRegistry();
    WorkspaceWatcher watcher =
        new WorkspaceWatcher(workspace, lifecycleStub(registry, new AgentLoader()));

    watcher.scanOnce();

    assertThat(registry.list()).isEmpty();
  }

  /** 原子 move 整目录进 agents/（scp/git 落盘形态：目录一次到位，AGENT.md 已就绪——与 cp 式半成品窗口区分）。 */
  private void dropCompleteDir(Path workspace, String name) throws Exception {
    Path staging = workspace.resolve("staging-" + name);
    Files.createDirectories(staging);
    Files.writeString(
        staging.resolve("AGENT.md"),
        "---\nname: " + name + "\nprovider:\n  name: deepseek\n---\n正文");
    Files.move(staging, agentDir(workspace, name));
  }

  private static void waitFor(java.util.function.BooleanSupplier condition, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(50);
    }
  }

  private static void deleteRecursively(Path dir) throws Exception {
    try (var stream = Files.walk(dir)) {
      stream
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(WorkspaceWatcherTest::deleteQuietly);
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (Exception ignored) {
      // 尽力删除
    }
  }
}
