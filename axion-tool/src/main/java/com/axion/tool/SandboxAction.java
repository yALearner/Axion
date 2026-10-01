package com.oryxos.tool;

/**
 * 一个待校验的涉外动作（类型 + 目标：路径 / 命令 / URL）。
 *
 * <p>纯数据契约，字面量照技术方案 §6.7：{@code SandboxAction = { type: ActionType, target: String }}。
 */
public record SandboxAction(ActionType type, String target) {}
