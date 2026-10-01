package com.oryxos.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** ProfileRegistryRuntimeTest（011 FR-3）——register 后立即可见、remove/exists 语义；坑五：非法配置报错与启动路径完全一致。 */
class ProfileRegistryRuntimeTest {

  private static Profile profile(String name) {
    return new Profile(
        name,
        null,
        new Profile.Identity(null, null),
        new Profile.ProviderRef("deepseek", null, null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(null, null));
  }

  @Test
  @DisplayName("register 后立即 findByName 可见；remove 幂等；exists 语义正确")
  void registerRemoveExistsSemantics() {
    ProfileRegistry registry = new ProfileRegistry();
    registry.register(profile("ops-agent"));

    assertThat(registry.findByName("ops-agent")).isPresent();
    assertThat(registry.exists("ops-agent")).isTrue();
    assertThat(registry.exists("other")).isFalse();

    registry.remove("ops-agent");
    assertThat(registry.findByName("ops-agent")).isEmpty();
    assertThat(registry.exists("ops-agent")).isFalse();

    assertThatNoException().isThrownBy(() -> registry.remove("ops-agent")); // 幂等注销：不存在时静默
  }

  @Test
  @DisplayName("坑五回归：非法配置经运行时注册路径报错与启动扫描路径完全一致（同一异常类型 + 同一消息）")
  void invalidConfigSameErrorOnBothPaths(@TempDir Path tmp) throws Exception {
    Path agentsRoot = Files.createDirectories(tmp.resolve("agents"));
    Path badDir = Files.createDirectories(agentsRoot.resolve("bad-agent"));
    Files.writeString(
        badDir.resolve("AGENT.md"), "---\nname: bad-agent\nprovider:\n  name: nope\n---\n正文\n");
    Set<String> providers = Set.of("deepseek");

    // 运行时注册路径：deriveProfile + register（30 节同段代码）——捕获异常与启动路径比消息
    IllegalArgumentException runtimeError;
    try {
      Profile p = new AgentLoader().deriveProfile(badDir, providers);
      new ProfileRegistry().register(p);
      throw new AssertionError("非法配置应当报错：deriveProfile 未抛出");
    } catch (IllegalArgumentException e) {
      runtimeError = e; // 单次赋值：effectively final，可在断言 lambda 内使用
    }

    // 启动扫描路径：loadAll 跳过并记错误日志——日志里的异常与运行时路径同一类型、同一消息
    Logger logger = (Logger) LoggerFactory.getLogger(AgentLoader.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      Map<String, Profile> loaded = new AgentLoader().loadAll(agentsRoot, providers);
      assertThat(loaded).isEmpty(); // 跳过不注册
    } finally {
      logger.detachAppender(appender);
    }
    assertThat(appender.list)
        .anySatisfy(
            event ->
                assertThat(event.getThrowableProxy().getMessage())
                    .isEqualTo(runtimeError.getMessage())); // 同一消息：两条路径不可漂移
  }
}
