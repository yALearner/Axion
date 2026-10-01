package com.oryxos.tool.notify;

/**
 * 出站通知通道统一接口（接口先行，FR-1）。
 *
 * <p>唯一方法表达"把一条内容送到某个通知目标"的意图——签名不出现"webhook""企业微信""飞书"等任何一档实现 特有的词；核心阶段只在接口后面挂一档实现（{@link
 * WebhookNotifyAdapter}），以后加新渠道只新增实现类、 不改接口、不改调用方（换企业微信官方 SDK 等专用实现，本签名同样干净套入）。
 *
 * <p>跨节契约（specs/004-notify/contracts/notify-channel.md）：后续节不得改动已验收行为。
 */
public interface NotifyChannelAdapter {

  /** 把 content 送到 target 指定的通知目标；失败异常原样上抛，不得静默吞掉（坑十一）。 */
  void send(NotifyTarget target, String content);
}
