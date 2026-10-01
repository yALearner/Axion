package com.oryxos.tool.notify;

import com.oryxos.storage.NotifyChannelEntity;
import com.oryxos.storage.NotifyChannelRepository;
import java.util.List;
import java.util.Map;

/**
 * 注册表解析服务（FR-5，纯数据）：按渠道名把 notify_channels 表行解析成 {@link NotifyTarget} （channelType = 表 type 列，config
 * 含 url 与渠道 name——name 供审计结果带渠道名用）。
 *
 * <p>查不到 → 明确报错，不静默、Agent 不会以为发出去了。{@code channel} 缺省口径（拍板 2026-09-04）：
 * 注册表恰好一条渠道才允许缺省取它，多条或为空时明确报错要求显式指定 channel——推错群是不可见的错误， 把出错窗口压到最小。
 *
 * <p>adapter 选择不在本类（拍板：本类纯数据，选择归 {@code NotifyTools} 的显式 Map——跨节契约越小越稳）。 不加
 * {@code @Component}（G4-C1 钉死，第 20 节装配处显式 {@code @Bean}）。
 */
public class NotifyChannelRegistry {

  private final NotifyChannelRepository repository;

  public NotifyChannelRegistry(NotifyChannelRepository repository) {
    this.repository = repository;
  }

  /**
   * 按渠道名解析；channel 为 null 时走缺省口径（注册表恰好一条才允许）。
   *
   * @throws IllegalArgumentException 渠道未注册 / 缺省时注册表多条或为空
   */
  public NotifyTarget resolve(String channel) {
    NotifyChannelEntity entity;
    if (channel != null) {
      entity =
          repository
              .findById(channel)
              .orElseThrow(() -> new IllegalArgumentException("通知渠道未注册: " + channel));
    } else {
      List<NotifyChannelEntity> all = repository.findAll();
      if (all.size() == 1) {
        entity = all.get(0);
      } else if (all.isEmpty()) {
        throw new IllegalArgumentException("未配置任何通知渠道，调用 notify 必须显式指定 channel");
      } else {
        throw new IllegalArgumentException(
            "注册表存在 " + all.size() + " 条通知渠道，调用 notify 必须显式指定 channel");
      }
    }
    return new NotifyTarget(
        entity.getType(),
        Map.of(NotifyTarget.KEY_URL, entity.getUrl(), NotifyTarget.KEY_NAME, entity.getName()));
  }
}
