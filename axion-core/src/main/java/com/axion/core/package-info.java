/**
 * OryxOS Core — 核心抽象和接口.
 *
 * <p>包含 OryxOS 运行时引擎的核心组件：
 *
 * <ul>
 *   <li>{@code OryxTool} 接口 — 所有 Tool 的统一抽象
 *   <li>{@code ReActLoop} — 自实现 ReAct 循环引擎
 *   <li>{@code PromptBuilder} — Prompt 组装器
 *   <li>{@code ToolExecutor} — Tool 调度执行器
 *   <li>{@code AgentService} — 三种触发源共用的统一入口
 *   <li>{@code AgentScheduler} — 定时任务（钟推）
 *   <li>{@code AgentLoader} — 扫 .oryxos/agents/ 派生 Profile
 *   <li>{@code ContextLoader} — Bootstrap + AGENT.md 正文加载
 *   <li>{@code Profile} / {@code Session} — 核心数据模型
 * </ul>
 */
package com.oryxos.core;
