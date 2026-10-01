/**
 * OryxOS Provider — 核心能力一：对接 LLM.
 *
 * <p>Provider 抽象层，包含：
 *
 * <ul>
 *   <li>{@code ProviderService} — 统一 LLM 调用入口
 *   <li>Provider name → ChatModel 显式映射
 *   <li>Function Calling 协议适配
 * </ul>
 *
 * <p>基于 Spring AI Alibaba 做协议转换，只用其 Provider 抽象和 协议转换能力，禁用其自动 Tool 执行。
 */
package com.oryxos.provider;
