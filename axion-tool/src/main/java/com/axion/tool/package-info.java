/**
 * OryxOS Tool — 核心能力四：工具体系.
 *
 * <p>三合一模块（内置 Tool + MCP Client + Sandbox + NotifyTools），包含：
 *
 * <ul>
 *   <li>内置 Tool：FileTools / ShellTools / HttpTools / NotifyTools
 *   <li>{@code ToolRegistry} — 统一 Tool 注册中心
 *   <li>{@code McpClientService} / {@code McpToolAdapter} — MCP 协议集成
 *   <li>{@code Sandbox} 接口 + {@code WhitelistSandbox} — 安全沙箱
 *   <li>{@code NotifyChannelAdapter} + {@code WebhookNotifyAdapter} — 通知推送
 * </ul>
 */
package com.oryxos.tool;
