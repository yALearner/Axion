package com.axion.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axion.storage.ScheduledTaskStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/**
 * AgentScanRegisterTest（011 FR-3/FR-4/FR-7）——扫一个放了 N 个 Agent 目录的目录 → ProfileRegistry 出现 N 个、带
 * schedules 的进 AgentScheduler；daily-reconcile fixture 派生断言（示例 Agent 与设计文档口径一致）。
 */
class AgentScanRegisterTest {

  /** 从测试资源拷 fixture 目录树到临时 agents 根（fixture 是本节交付物，git 可提交；junction 绑定由测试程序另建）。 */
  private static Path copyFixture(String resourcePath, Path target) throws Exception {
    Path source =
        Path.of(AgentScanRegisterTest.class.getClassLoader().getResource(resourcePath).toURI());
    try (Stream<Path> files = Files.walk(source)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        Path targetFile = target.resolve(source.relativize(file).toString());
        Files.createDirectories(targetFile.getParent());
        Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
      }
    }
    return target;
  }

  @Test
  @DisplayName("N 个目录扫描注册：注册表 N 个（坏目录跳过）、带 schedules 的进 AgentScheduler")
  void scanRegistersAllAgents(@TempDir Path tmp) throws Exception {
    Path agentsRoot = Files.createDirectories(tmp.resolve("agents"));
    copyFixture("agents/daily-reconcile", agentsRoot.resolve("daily-reconcile"));
    // 第二个带 schedules 的目录
    Path two = Files.createDirectories(agentsRoot.resolve("two-schedules"));
    Files.writeString(
        two.resolve("AGENT.md"),
        """
        ---
        name: two-schedules
        provider:
          name: deepseek
        schedules:
          - id: morning
            cron: "0 0 9 * * *"
            zone: Asia/Shanghai
            message: 早上好
          - id: evening
            cron: "0 30 18 * * *"
            message: 晚上好
        ---
        正文
        """);
    // 无 schedules 的目录
    Path bare = Files.createDirectories(agentsRoot.resolve("bare-agent"));
    Files.writeString(
        bare.resolve("AGENT.md"), "---\nname: bare-agent\nprovider:\n  name: deepseek\n---\n正文\n");
    // 坏目录（provider 不存在）：跳过不阻断
    Path bad = Files.createDirectories(agentsRoot.resolve("bad-agent"));
    Files.writeString(
        bad.resolve("AGENT.md"), "---\nname: bad-agent\nprovider:\n  name: nope\n---\n正文\n");

    ProfileRegistry registry = new ProfileRegistry();
    new AgentLoader().loadAll(agentsRoot, Set.of("deepseek")).values().forEach(registry::register);

    ThreadPoolTaskScheduler taskScheduler = mock(ThreadPoolTaskScheduler.class);
    when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
        .thenAnswer(inv -> mock(ScheduledFuture.class));
    ScheduledTaskStore store = mock(ScheduledTaskStore.class);
    when(store.isEnabled(anyString())).thenReturn(true);
    AgentScheduler scheduler =
        new AgentScheduler(
            taskScheduler, registry, mock(SessionManager.class), mock(AgentService.class), store);
    scheduler.registerAll();

    assertThat(registry.list()).hasSize(3); // 好目录全注册、坏目录跳过
    assertThat(registry.exists("daily-reconcile")).isTrue();
    assertThat(registry.exists("two-schedules")).isTrue();
    assertThat(registry.exists("bare-agent")).isTrue();
    assertThat(registry.exists("bad-agent")).isFalse();
    assertThat(scheduler.scheduledTaskCount()).isEqualTo(3); // daily-reconcile 1 + two-schedules 2
  }

  @Test
  @DisplayName("daily-reconcile fixture 派生断言：tools/schedules/provider 与设计文档口径一致")
  void dailyReconcileFixtureDerivesAsDocumented(@TempDir Path tmp) throws Exception {
    Path agentsRoot = Files.createDirectories(tmp.resolve("agents"));
    copyFixture("agents/daily-reconcile", agentsRoot.resolve("daily-reconcile"));

    Profile profile =
        new AgentLoader().deriveProfile(agentsRoot.resolve("daily-reconcile"), Set.of("deepseek"));

    assertThat(profile.name()).isEqualTo("daily-reconcile");
    assertThat(profile.provider().name()).isEqualTo("deepseek");
    assertThat(profile.provider().model()).isEqualTo("deepseek-chat");
    assertThat(profile.provider().temperature()).isEqualTo(0.2);
    assertThat(profile.tools()).containsExactly("shell", "read_file", "notify", "save_memory");
    assertThat(profile.schedules()).hasSize(1);
    assertThat(profile.schedules().get(0))
        .isEqualTo(
            new Profile.Schedule(
                "reconcile-morning", "0 0 9 * * *", "Asia/Shanghai", "到点了，核对昨天的订单对账。"));
    assertThat(new AgentLoader().detectResources(agentsRoot.resolve("daily-reconcile")))
        .containsExactlyInAnyOrder("scripts", "REFERENCE.md"); // skills/ 绑定是测试期程序创建的，fixture 不预置
  }
}
