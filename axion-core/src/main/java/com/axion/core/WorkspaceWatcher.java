package com.axion.core;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 目录监听器（012-agent-mgmt FR-1）——第二条录入路径（手工丢目录）的执行者：启动先全量扫一遍，之后用 JDK {@link WatchService} 实时监听 {@code
 * .axion/agents/} 变更——新增/改 → {@link AgentLifecycleService#register}（与 API
 * 上传写完目录后调的**同一个方法**，坑十七）；删除/移走 → {@link AgentLifecycleService#unregister}（与 API 删除同一段 注销代码）。
 *
 * <p>装配层起守护线程（与 AgentScheduler 调度线程同类，R10——不进请求链路，宪法 VII 不违反）；单个坏目录 WARN 跳过**不拖垮监听**（课件
 * 2.1）；目录"写一半"产生的坏事件由下一次目录 MODIFY 事件（Windows/Linux 写文件触发目录 修改时间变化）自然重试拾取（R5）。macOS
 * 不监听子目录内文件改动的差异由编辑 AGENT.md 显式走 update 重注册兜底（坑十三）。
 */
public final class WorkspaceWatcher {

  private static final Logger LOG = LoggerFactory.getLogger(WorkspaceWatcher.class);

  /** 半成品目录的重试上限（3 次 × 1s——cp 小目录的落盘窗口量级）。 */
  private static final int REGISTER_MAX_ATTEMPTS = 3;

  private static final long REGISTER_RETRY_DELAY_MS = 1000;

  private final Path workspaceRoot;
  private final AgentLifecycleService lifecycle;
  private volatile boolean running = true;
  private WatchService watchService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "AgentLifecycleService 为装配处注入的单例（只读使用、不暴露引用）——AgentScheduler 同款先例")
  public WorkspaceWatcher(Path workspaceRoot, AgentLifecycleService lifecycle) {
    this.workspaceRoot = workspaceRoot;
    this.lifecycle = lifecycle;
  }

  /** 启动全量扫（AgentLoader.loadAll 同款遍历口径）：逐目录 register，坏目录 WARN 跳过不阻断。 */
  public void scanOnce() {
    Path agentsRoot = workspaceRoot.resolve("agents");
    if (!Files.isDirectory(agentsRoot)) {
      return; // 未初始化是合法空态（先执行 axion init）
    }
    try (DirectoryStream<Path> dirs = Files.newDirectoryStream(agentsRoot)) {
      for (Path dir : dirs) {
        if (!Files.isDirectory(dir)) {
          continue;
        }
        registerSafely(dir);
      }
    } catch (IOException e) {
      LOG.warn("工作区扫描失败，跳过本轮", e);
    }
  }

  /** 守护线程主循环：全量扫 + WatchService 实时监听；{@link #stop()} 或线程中断退出。 */
  public void run() {
    scanOnce();
    Path agentsRoot = workspaceRoot.resolve("agents");
    try {
      Files.createDirectories(agentsRoot);
      watchService = FileSystems.getDefault().newWatchService();
      agentsRoot.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
    } catch (IOException e) {
      LOG.error("工作区监听启动失败（启动扫描兜底仍有效）", e);
      return;
    }
    LOG.info("工作区监听已启动: .axion/agents");
    while (running) {
      WatchKey key;
      try {
        key = watchService.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (ClosedWatchServiceException e) {
        break;
      }
      for (WatchEvent<?> event : key.pollEvents()) {
        WatchEvent.Kind<?> kind = event.kind();
        if (OVERFLOW.name().equals(kind.name())) {
          continue;
        }
        Path name = (Path) event.context(); // 相对文件名（目录名）
        onAgentDirChanged(agentsRoot.resolve(name), kind);
      }
      if (!key.reset()) {
        break;
      }
    }
    LOG.info("工作区监听已停止");
  }

  /** 测试钩子（AgentScheduler.scheduledTaskCount 同款）：监听是否已注册——测试在变更目录前等它就绪（防启动竞态）。 */
  boolean isWatching() {
    return watchService != null;
  }

  /** 停止监听（守护线程退出：running 置 false + 关闭 WatchService 唤醒 take()）。 */
  public void stop() {
    running = false;
    WatchService service = watchService;
    if (service != null) {
      try {
        service.close();
      } catch (IOException ignored) {
        // 关闭失败不阻退出
      }
    }
  }

  /** 变更事件处理（课件 2.1 尾部代码）：删除 → 注销；新增/改 → 同一段 register(agentDir)；坏目录 WARN 跳过。 */
  private void onAgentDirChanged(Path agentDir, WatchEvent.Kind<?> kind) {
    Path fileName = agentDir.getFileName(); // 单次取值入局部变量再判空（011 实录：@Nullable 二次调用判空无效）
    if (fileName == null) {
      return;
    }
    String name = fileName.toString();
    try {
      if (ENTRY_DELETE.name().equals(kind.name())) {
        lifecycle.unregister(name); // 目录已不在——按名注销（与 API 删除同一段注销代码）
      } else if (Files.isDirectory(agentDir)) {
        registerSafely(agentDir);
      }
      // 其余事件（agents 根下的散文件等）忽略
    } catch (RuntimeException e) {
      LOG.warn("Agent 目录 {} 变更处理失败，跳过：{}", sanitize(name), sanitize(e.getMessage()));
    }
  }

  /** 注册 + 有限重试（R5：cp -r 式"目录先建、文件后写"的半成品窗口——CREATE 事件到达时 AGENT.md 可能还没落盘）。 */
  private void registerSafely(Path agentDir) {
    for (int attempt = 1; ; attempt++) {
      try {
        lifecycle.register(agentDir);
        return;
      } catch (RuntimeException e) {
        if (attempt >= REGISTER_MAX_ATTEMPTS) {
          LOG.warn(
              "Agent 目录 {} 变更处理失败，跳过：{}",
              sanitize(agentDir.getFileName()),
              sanitize(e.getMessage()));
          return;
        }
        try {
          Thread.sleep(REGISTER_RETRY_DELAY_MS); // 守护线程内等待（不进请求链路，宪法 VII 不违反）
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  /** 日志参数净化 CR/LF（CRLF 注入防线，AgentScheduler 同款）。 */
  private static String sanitize(Object value) {
    return String.valueOf(value).replace('\r', '?').replace('\n', '?');
  }
}
