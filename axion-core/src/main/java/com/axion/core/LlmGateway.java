package com.oryxos.core;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * LLM 调用端口（依赖倒置，G2 拍板 2026-09-01）。
 *
 * <p>ReActLoop 依赖本端口而非 oryxos-provider 的具体类（依赖方向铁律 core ← provider）；签名逐字与 {@code
 * ProviderService.chat} 一致，装配时以 ProviderService 实例注入。行为不变量（路由/审计/自动执行关闭） 由 001 的 ProviderService
 * 契约保证，见 contracts/react-loop.md。
 */
public interface LlmGateway {

  /**
   * 一次同步阻塞调用。
   *
   * @param sessionId 发起调用的会话标识（审计按此关联）
   * @param profile Agent 运行时配置（路由与默认调用参数的来源）
   * @param prompt 要发送的内容（消息列表 + 可用工具）
   */
  ChatResponse chat(String sessionId, Profile profile, Prompt prompt);
}
