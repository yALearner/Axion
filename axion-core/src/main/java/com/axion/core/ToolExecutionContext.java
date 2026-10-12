package com.axion.core;

/**
 * 工具执行上下文（012-agent-mgmt FR-8，5.2.1 per-agent 记忆的载体）——携带"当前工具在替哪个 Agent 跑"的 ThreadLocal 标记。
 *
 * <p>置入点：{@link ToolExecutor#execute} 执行工具前置入 {@code profile.name()}；读路径
 * （MemoryService.buildContext/readAll）由 MemoryServiceImpl 委托 store 前后临时置入。置入/清除必须成对 （finally
 * 必清，坑十六——002 坑四同款泄漏纪律：泄漏只在并发复用时串号，核心阶段全程同步单线程 安全）。
 */
public final class ToolExecutionContext {

  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

  private ToolExecutionContext() {}

  /** 置入当前 Agent 名（重复置入覆盖——同步模型下同一时刻只有一个 Agent 在执行）。 */
  public static void set(String agentName) {
    CURRENT.set(agentName);
  }

  /** 当前 Agent 名；无上下文返回 {@code null}（调用方按回退语义处理）。 */
  public static String current() {
    return CURRENT.get();
  }

  /** 清除（finally 必调，坑十六）。 */
  public static void clear() {
    CURRENT.remove();
  }
}
