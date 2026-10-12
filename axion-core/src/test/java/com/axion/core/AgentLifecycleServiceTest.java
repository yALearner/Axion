package com.axion.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** 编排顺序与失败回滚 harness（012 FR-2/FR-3，课件 §八两个最值钱测试原文收录）——InOrder 钉顺序、异常注入钉回滚。 */
class AgentLifecycleServiceTest {

  private final AgentLoader agentLoader = mock(AgentLoader.class);
  private final ProfileRegistry profileRegistry = new ProfileRegistry();
  private final AgentScheduler agentScheduler = mock(AgentScheduler.class);
  private final AgentStore agentStore = mock(AgentStore.class);
  private final LlmGateway llmGateway = mock(LlmGateway.class);
  private final java.util.function.Consumer<Profile> toolRefValidator =
      mock(java.util.function.Consumer.class);

  private AgentLifecycleService lifecycle;

  private static Profile profile(String name, List<Profile.Schedule> schedules) {
    return new Profile(
        name,
        null,
        new Profile.Identity(name, "指令"),
        new Profile.ProviderRef("deepseek", "deepseek-chat", 0.7),
        List.of(),
        List.of(),
        List.of(),
        schedules,
        List.of(),
        new Profile.Settings(10, 20));
  }

  @BeforeEach
  void setUp() {
    when(agentStore.scaffold(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(
            inv ->
                Map.of(
                    "AGENT.md",
                    "---\nname: "
                        + inv.getArgument(0)
                        + "\ndescription: "
                        + inv.getArgument(1)
                        + "\nprovider:\n  name: deepseek\n---\n正文",
                    "scripts/.keep",
                    "",
                    "skills/.keep",
                    "",
                    "REFERENCE.md",
                    "# 参考"));
    when(agentStore.writeAll(anyString(), any()))
        .thenReturn(Path.of(".axion", "agents", "ops-agent"));
    lifecycle =
        new AgentLifecycleService(
            agentLoader,
            profileRegistry,
            agentScheduler,
            agentStore,
            llmGateway,
            toolRefValidator,
            Set.of("deepseek"),
            "deepseek",
            "deepseek-chat");
  }

  @Test
  @DisplayName("create 按序执行：重名第一步就拒（坑十八，一个目录都不写）")
  void createRejectsDuplicateNameBeforeWritingAnything() {
    profileRegistry.register(profile("ops-agent", List.of()));

    assertThatThrownBy(() -> lifecycle.create("ops-agent", "运维助手"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ops-agent");

    verify(agentStore, never()).writeAll(anyString(), any()); // 一个目录都不写
    verify(agentScheduler, never()).registerProfile(any());
  }

  @Test
  @DisplayName("注册失败_必须回滚已写的Agent目录_不留半个Agent（课件 §八最值钱原文）")
  void createRollsBackWrittenDirectoryOnRegistrationFailure() {
    when(agentLoader.deriveProfile(any(), any()))
        .thenThrow(new IllegalArgumentException("frontmatter 非法"));

    assertThatThrownBy(() -> lifecycle.create("half", "半个 Agent"))
        .isInstanceOf(IllegalArgumentException.class);

    verify(agentStore).delete(any()); // 已写的目录被删回去
    verify(agentScheduler, never()).registerProfile(any()); // 定时根本没走到
    assertThat(profileRegistry.exists("half")).isFalse(); // 系统里干干净净
  }

  @Test
  @DisplayName("create 与 watcher 走同一段 register(agentDir)（坑十七）：两条路同一注册结果")
  void createAndWatcherShareSameRegisterPath() {
    Profile derived = profile("ops-agent", List.of());
    when(agentLoader.deriveProfile(any(), any())).thenReturn(derived);
    when(agentStore.agentDirOf("ops-agent")).thenReturn(Path.of(".axion", "agents", "ops-agent"));

    Profile viaCreate = lifecycle.create("ops-agent", "运维助手");
    Profile viaRegister = lifecycle.register(Path.of(".axion", "agents", "ops-agent"));

    assertThat(viaCreate.name()).isEqualTo("ops-agent");
    assertThat(viaRegister.name()).isEqualTo("ops-agent");
    verify(toolRefValidator, org.mockito.Mockito.times(2)).accept(derived); // 同一段注册代码的校验钩子都跑
  }

  @Test
  @DisplayName("删除必须先停定时_再动索引和目录（课件 §八最值钱原文，坑十）")
  void deleteStopsSchedulerBeforeTouchingRegistryAndDir() {
    profileRegistry.register(
        profile(
            "weather-daily", List.of(new Profile.Schedule("w-8am", "0 0 8 * * *", null, "早安"))));

    lifecycle.delete("weather-daily");

    InOrder o = inOrder(agentScheduler, agentStore);
    o.verify(agentScheduler).unregisterProfile(any()); // 顺序反了：定时还在跑、Profile 已没——触发即空指针
    o.verify(agentStore).archive("weather-daily");
    assertThat(profileRegistry.exists("weather-daily")).isFalse();
  }

  @Test
  @DisplayName("update：整份 AGENT.md 覆写重注册；schedules 变更先 unregister 后 register")
  void updateReRegistersAndUnregistersOldSchedulesFirst() {
    Profile oldProfile =
        profile("ops-agent", List.of(new Profile.Schedule("old-job", "0 0 8 * * *", null, "早安")));
    Profile newProfile =
        profile("ops-agent", List.of(new Profile.Schedule("new-job", "0 30 9 * * *", null, "日报")));
    profileRegistry.register(oldProfile);
    when(agentLoader.deriveProfile(any(), any())).thenReturn(newProfile);

    Profile updated = lifecycle.update("ops-agent", "新 AGENT.md");

    InOrder o = inOrder(agentScheduler);
    assertThat(updated).isEqualTo(newProfile);
    o.verify(agentScheduler).unregisterProfile(oldProfile); // 旧句柄先注销
    o.verify(agentScheduler).registerProfile(newProfile); // 新句柄后注册
  }

  @Test
  @DisplayName("update：schedules 未变更不重复注册定时（句柄不变，避免 id 冲突）")
  void updateWithSameSchedulesSkipsSchedulerReRegistration() {
    Profile sameSchedules =
        profile("ops-agent", List.of(new Profile.Schedule("same-job", "0 0 8 * * *", null, "早安")));
    profileRegistry.register(sameSchedules);
    when(agentLoader.deriveProfile(any(), any())).thenReturn(sameSchedules);

    lifecycle.update("ops-agent", "改个措辞");

    verify(agentScheduler, never()).unregisterProfile(any());
    verify(agentScheduler, never()).registerProfile(any());
    assertThat(profileRegistry.findByName("ops-agent")).contains(sameSchedules); // 注册表已更新
  }

  @Test
  @DisplayName("generateFiles：一次 LlmGateway 调用、解析校验（坑十四）、剥围栏、不落盘不注册")
  void generateFilesCallsGatewayOnceAndStripsFences() {
    Generation generation = mock(Generation.class);
    org.springframework.ai.chat.messages.AssistantMessage output =
        new org.springframework.ai.chat.messages.AssistantMessage(
            "```markdown\n---\nname: ops-agent\n---\n正文\n```");
    when(generation.getOutput()).thenReturn(output);
    when(llmGateway.chat(anyString(), any(), any()))
        .thenReturn(new ChatResponse(List.of(generation)));

    Map<String, String> files = lifecycle.generateFiles("s-1", "ops-agent", "查天气");

    assertThat(files.get("AGENT.md")).contains("name: ops-agent").doesNotContain("```");
    verify(agentStore, never()).writeAll(anyString(), any()); // 不落盘
    verify(agentScheduler, never()).registerProfile(any()); // 不注册
  }

  @Test
  @DisplayName("坑十四：生成产物非法 → IllegalArgumentException（web 层映射 400），不落盘")
  void generateFilesRejectsUnparsableOutput() {
    Generation generation = mock(Generation.class);
    when(generation.getOutput())
        .thenReturn(new org.springframework.ai.chat.messages.AssistantMessage("这不是 AGENT.md"));
    when(llmGateway.chat(anyString(), any(), any()))
        .thenReturn(new ChatResponse(List.of(generation)));

    assertThatThrownBy(() -> lifecycle.generateFiles("s-1", "ops-agent", "查天气"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不是合法 AGENT.md");
    verify(agentStore, never()).writeAll(anyString(), any());
  }

  @Test
  @DisplayName("坑十五：author model 留空 → IllegalArgumentException（web 层映射 503），不向模型发 model=null")
  void generateFilesFailsFastWhenModelMissing() {
    AgentLifecycleService noModel =
        new AgentLifecycleService(
            agentLoader,
            profileRegistry,
            agentScheduler,
            agentStore,
            llmGateway,
            toolRefValidator,
            Set.of("deepseek"),
            "deepseek",
            null);

    assertThatThrownBy(() -> noModel.generateFiles("s-1", "ops-agent", "查天气"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("axion.author.model");
    verify(llmGateway, never()).chat(anyString(), any(), any()); // 不发 model=null
  }
}
