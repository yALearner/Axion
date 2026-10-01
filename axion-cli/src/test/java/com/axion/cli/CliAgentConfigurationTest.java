package com.axion.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axion.core.LongTermMemoryStore;
import com.axion.core.MemoryService;
import com.axion.core.Message;
import com.axion.core.Profile;
import com.axion.core.PromptBuilder;
import com.axion.core.Session;
import com.axion.core.SessionManager;
import com.axion.memory.MarkdownMemoryStore;
import com.axion.memory.MemoryServiceImpl;
import com.axion.provider.ProviderService;
import com.axion.storage.NotifyChannelRepository;
import com.axion.storage.ScheduledTaskRepository;
import com.axion.storage.SessionRepository;
import com.axion.storage.TaskExecutionRepository;
import com.axion.storage.ToolInvocationRepository;
import com.axion.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

/**
 * CliAgentConfiguration 记忆装配 harness（2026-09-06 用户指示补强——cli 模块此前零测试）： US1 装配断言（T010）：两记忆 Tool 注册 +
 * MemoryService/缺省 markdown 档装配 + 注入 PromptBuilder； US3 换档断言（T025）在同文件增补。
 */
class CliAgentConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(CliAgentConfiguration.class, MockBeans.class);

  /** 装配依赖桩：仓储/ProviderService 全 mock（不启 JPA/真实 LLM），ObjectMapper 真实例。 */
  @TestConfiguration
  static class MockBeans {

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    SessionRepository sessionRepository() {
      SessionRepository repository = mock(SessionRepository.class);
      when(repository.findById(anyString())).thenReturn(java.util.Optional.empty());
      return repository;
    }

    @Bean
    ToolInvocationRepository toolInvocationRepository() {
      return mock(ToolInvocationRepository.class);
    }

    @Bean
    NotifyChannelRepository notifyChannelRepository() {
      return mock(NotifyChannelRepository.class);
    }

    @Bean
    ScheduledTaskRepository scheduledTaskRepository() {
      return mock(ScheduledTaskRepository.class);
    }

    @Bean
    TaskExecutionRepository taskExecutionRepository() {
      return mock(TaskExecutionRepository.class);
    }

    @Bean
    ProviderService providerService() {
      return mock(ProviderService.class);
    }

    @Bean
    javax.sql.DataSource dataSource() {
      return mock(javax.sql.DataSource.class);
    }
  }

  private static Profile profileWithEmptyBootstrap() {
    return new Profile(
        "ops-agent",
        null,
        new Profile.Identity("运维小欧", "你是一个专业运维助手"),
        new Profile.ProviderRef("deepseek", null, null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(10, 20));
  }

  @Test
  @DisplayName(
      "US1 装配：save_memory/recall_memory 注册进 ToolRegistry，缺省 backend 装配 MarkdownMemoryStore")
  void memoryToolsRegisteredAndMarkdownWired() {
    runner.run(
        context -> {
          ToolRegistry registry = context.getBean(ToolRegistry.class);
          assertThat(registry.contains("save_memory")).isTrue();
          assertThat(registry.contains("recall_memory")).isTrue();

          MemoryService memoryService = context.getBean(MemoryService.class);
          assertThat(memoryService).isInstanceOf(MemoryServiceImpl.class);
          assertThat(context.getBean(LongTermMemoryStore.class))
              .isInstanceOf(MarkdownMemoryStore.class);
        });
  }

  @Test
  @DisplayName("MemoryService 注入 PromptBuilder：build 后 system 含长期记忆段")
  void memoryServiceInjectedIntoPromptBuilder() throws Exception {
    // 011 改造点适配：ContextLoader 现注入 AGENT.md 正文（FR-2）——fixture 建 .axion/agents/ops-agent
    // （frontmatter-only 且无尾换行：正文为空；load 在 build 时现读，故在 runner.run 之前建）
    java.nio.file.Path agentDir =
        java.nio.file.Files.createDirectories(Path.of(".axion", "agents", "ops-agent"));
    java.nio.file.Files.writeString(agentDir.resolve("AGENT.md"), "---\nname: ops-agent\n---");
    runner.run(
        context -> {
          Session session =
              context.getBean(SessionManager.class).getOrCreate("cli", "alice", "ops-agent");
          session.append(Message.user("hi"));

          PromptBuilder promptBuilder = context.getBean(PromptBuilder.class);
          String system =
              promptBuilder
                  .build(session, profileWithEmptyBootstrap())
                  .getInstructions()
                  .get(0)
                  .getText();

          // 测试环境无 .axion/memory/MEMORY.md：load 为空，但 buildContext 结构头仍在
          assertThat(system).contains("## 长期记忆").contains("## 会话历史").contains("[用户] hi");
        });
  }

  @Test
  @DisplayName("换档 sqlite：backend=sqlite → LongTermMemoryStore bean 为 SqliteMemoryStore")
  void sqliteBackendWired() {
    runner
        .withPropertyValues("axion.memory.backend=sqlite")
        .run(
            context ->
                assertThat(context.getBean(LongTermMemoryStore.class))
                    .isInstanceOf(com.axion.memory.SqliteMemoryStore.class));
  }

  @Test
  @DisplayName("换档 mem0：backend=mem0 + 凭证环境变量 → LongTermMemoryStore bean 为 Mem0MemoryStore")
  void mem0BackendWired() {
    runner
        .withPropertyValues(
            "axion.memory.backend=mem0",
            "MEM0_BASE_URL=http://localhost:9999",
            "MEM0_API_KEY=test-key")
        .run(
            context ->
                assertThat(context.getBean(LongTermMemoryStore.class))
                    .isInstanceOf(com.axion.memory.Mem0MemoryStore.class));
  }

  @Test
  @DisplayName("mem0 缺凭证：backend=mem0 无 MEM0_BASE_URL → 启动失败明确报错（FR-6 配置校验不静默）")
  void mem0WithoutCredentialsFails() {
    runner
        .withPropertyValues("axion.memory.backend=mem0")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("非法 backend 值 → 启动失败明确报错（FR-6，001 ConfigLoader 口径不静默）")
  void illegalBackendFails() {
    runner
        .withPropertyValues("axion.memory.backend=bogus")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("008 FR-5 装配：AgentScheduler bean 存在 + 调度线程池 corePoolSize>1（⑦a 回归钉）")
  void agentSchedulerWiredWithPoolSize() {
    runner.run(
        context -> {
          assertThat(context.getBean(com.axion.core.AgentScheduler.class))
              .isInstanceOf(com.axion.core.AgentScheduler.class);
          org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler =
              context.getBean(
                  org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class);
          assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
              .isGreaterThan(1);
        });
  }

  @Test
  @DisplayName("007 FR-6 装配替换：sandbox bean 为 WhitelistSandbox 实例（PermissiveSandbox 全放行退出历史）")
  void sandboxBeanIsWhitelistSandbox() {
    runner.run(
        context ->
            assertThat(context.getBean(com.axion.tool.Sandbox.class))
                .isInstanceOf(com.axion.tool.WhitelistSandbox.class));
  }

  @Test
  @DisplayName("007 FR-6：PermissiveSandbox 类已删除（javadoc 承诺「24 节替换后本类删除」兑现）")
  void permissiveSandboxClassIsGone() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> Class.forName("com.axion.tool.PermissiveSandbox"))
        .isInstanceOf(ClassNotFoundException.class);
  }
}
