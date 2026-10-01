package com.oryxos.core;

/**
 * 当前处理的 Agent 上下文（ThreadLocal，虚拟线程下每请求天然独立）。
 *
 * <p>解决"工具执行时怎么知道当前是哪个 Agent"：{@link OryxTool#execute} 的签名不带 Profile，按 Profile 取配置
 * 这类需求从本上下文读，不改工具接口（技术方案 §4.2）。
 *
 * <p>铁律（坑四）：处理结束**必须**清理——{@link AgentService#process} 的 finally 负责；泄漏在单请求测试里永远
 * 不报错，只在并发复用时串号，是最阴险的一类 bug（AgentServiceTest 钉死）。
 */
public final class ProfileContext {

  private static final ThreadLocal<Profile> CURRENT = new ThreadLocal<>();

  private ProfileContext() {
    // 工具类，不实例化
  }

  /** 设置当前 Agent 配置（一次处理的开始）。 */
  public static void set(Profile profile) {
    CURRENT.set(profile);
  }

  /** 取当前 Agent 配置；不在处理流程内时返回 null。 */
  public static Profile current() {
    return CURRENT.get();
  }

  /** 清理（一次处理的结束，finally 中调用）。 */
  public static void clear() {
    CURRENT.remove();
  }
}
