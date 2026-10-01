package com.oryxos.tool;

/**
 * 沙箱校验失败异常——工具执行被拒绝时抛出，信息说明被拒动作。
 *
 * <p>由各工具（第 20 节起）在 {@code execute} 首行的 {@link Sandbox#enforce} 处触发；异常信息复用 ToolExecutor 的失败审计路径写入
 * {@code tool_invocations}（success=false + error_message）。
 */
public class SandboxViolationException extends RuntimeException {

  public SandboxViolationException(String message) {
    super(message);
  }
}
