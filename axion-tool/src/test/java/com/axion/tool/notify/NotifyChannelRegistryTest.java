package com.oryxos.tool.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.oryxos.storage.NotifyChannelEntity;
import com.oryxos.storage.NotifyChannelRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * NotifyChannelRegistry 验收 harness——按名解析（纯数据）：命中返回 NotifyTarget（config 含 url+name）； 未命中明确报错；channel
 * 缺省三态（恰好一条取它 / 多条报错 / 空报错，拍板口径）。
 */
class NotifyChannelRegistryTest {

  private final NotifyChannelRepository repository = mock(NotifyChannelRepository.class);
  private final NotifyChannelRegistry registry = new NotifyChannelRegistry(repository);

  @Test
  @DisplayName("按名命中：channelType 取 type 列，config 含 url 与渠道 name")
  void resolveByNameReturnsTargetWithUrlAndName() {
    when(repository.findById("team-lark"))
        .thenReturn(
            Optional.of(
                new NotifyChannelEntity(
                    "team-lark",
                    "webhook",
                    "https://open.feishu.cn/open-apis/bot/v2/hook/xxx",
                    "团队群机器人")));

    NotifyTarget target = registry.resolve("team-lark");

    assertThat(target.channelType()).isEqualTo("webhook");
    assertThat(target.config())
        .containsEntry("url", "https://open.feishu.cn/open-apis/bot/v2/hook/xxx")
        .containsEntry("name", "team-lark");
  }

  @Test
  @DisplayName("未命中：明确报错，不静默（错误信息带渠道名）")
  void missingChannelThrowsWithChannelName() {
    when(repository.findById("no-such-channel")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> registry.resolve("no-such-channel"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no-such-channel");
  }

  @Test
  @DisplayName("channel 缺省且注册表恰好一条：取这条唯一渠道（拍板口径）")
  void defaultChannelTakenWhenExactlyOne() {
    when(repository.findAll())
        .thenReturn(
            List.of(
                new NotifyChannelEntity(
                    "team-lark",
                    "webhook",
                    "https://open.feishu.cn/open-apis/bot/v2/hook/xxx",
                    null)));

    NotifyTarget target = registry.resolve(null);

    assertThat(target.config()).containsEntry("name", "team-lark");
  }

  @Test
  @DisplayName("channel 缺省且注册表多条：明确报错要求显式指定 channel（拍板口径）")
  void defaultChannelRejectedWhenMultiple() {
    when(repository.findAll())
        .thenReturn(
            List.of(
                new NotifyChannelEntity("team-lark", "webhook", "https://a.example.com/hook", null),
                new NotifyChannelEntity(
                    "team-prod", "webhook", "https://b.example.com/hook", null)));

    assertThatThrownBy(() -> registry.resolve(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("channel 缺省且注册表为空：明确报错（拍板口径）")
  void defaultChannelRejectedWhenEmpty() {
    when(repository.findAll()).thenReturn(List.of());

    assertThatThrownBy(() -> registry.resolve(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("换渠道回归：两条渠道按名各自命中不串；同一 name 换 URL 后解析取新 URL（换渠道不碰 Agent）")
  void sameNameNewUrlResolvesToNewUrl() {
    when(repository.findById("team-lark"))
        .thenReturn(
            Optional.of(
                new NotifyChannelEntity(
                    "team-lark", "webhook", "https://prod.example.com/hook", null)))
        .thenReturn(
            Optional.of(
                new NotifyChannelEntity(
                    "team-lark", "webhook", "https://test.example.com/hook", null)));

    assertThat(registry.resolve("team-lark").config().get("url"))
        .isEqualTo("https://prod.example.com/hook");
    // 运营把注册表里同一 name 的 url 改成新地址后，Agent 按名引用的写法零改动，解析取新 URL
    assertThat(registry.resolve("team-lark").config().get("url"))
        .isEqualTo("https://test.example.com/hook");
  }
}
