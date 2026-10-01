package com.axion.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 三种触发源共用的统一入口，也是一次处理的编排者（宪法 VIII）。
 *
 * <p>{@link #process(Session, String)} 依次：拿 per-session 锁（⑨d）→ 从 {@link ProfileRegistry} 按
 * session.profileName() 取 Profile → 放进 {@link ProfileContext} → 跑 {@link ReActLoop} → {@link
 * SessionManager#save} 持久化 → **finally 清理 ProfileContext + 放锁**（坑四：处理抛异常也必须清）。ReActLoop
 * 不感知消息从哪个入口来——CLI / Web Service / AgentScheduler 第 18 节起接入本入口。
 *
 * <p>⑨d 并发口径（009-web-service）：三触发源第一次同场后，Web 并发请求 + 钟推可能同时 process 同一 Session——per-session
 * 锁把同一会话串行化（排队语义；Web 侧排队超时由 60s 上限兜住），不同会话互不阻塞。核心阶段单实例，本锁只解决进程内并发（008 同款口径）。
 */
public final class AgentService {

  private final ProfileRegistry profileRegistry;
  private final ReActLoop reActLoop;
  private final SessionManager sessionManager;
  private final ConcurrentMap<String, Lock> sessionLocks = new ConcurrentHashMap<>();

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "ProfileRegistry/ReActLoop/SessionManager 为装配处注入的单例（只读使用、不暴露引用）——AgentScheduler 同款先例")
  public AgentService(
      ProfileRegistry profileRegistry, ReActLoop reActLoop, SessionManager sessionManager) {
    this.profileRegistry = profileRegistry;
    this.reActLoop = reActLoop;
    this.sessionManager = sessionManager;
  }

  /** 处理一条用户消息，返回 Agent 最终答复。 */
  public String process(Session session, String userMessage) {
    Lock lock = sessionLocks.computeIfAbsent(session.id(), k -> new ReentrantLock());
    lock.lock(); // ⑨d：同一会话串行化（排队；不同会话并行不受影响）
    try {
      Profile profile =
          profileRegistry
              .findByName(session.profileName())
              .orElseThrow(
                  () -> new IllegalStateException("Profile 未注册: " + session.profileName()));
      ProfileContext.set(profile);
      try {
        String reply = reActLoop.run(session, userMessage, profile);
        sessionManager.save(session);
        return reply;
      } finally {
        ProfileContext.clear();
      }
    } finally {
      lock.unlock();
    }
  }
}
