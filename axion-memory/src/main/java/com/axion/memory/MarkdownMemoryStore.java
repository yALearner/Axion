package com.oryxos.memory;

import com.oryxos.core.LongTermMemoryStore;
import com.oryxos.core.MemoryScope;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 长期记忆默认档（FR-3）：底层 {@code .oryxos/memory/MEMORY.md} 一个文件、{@code ## 核心记忆}/{@code ## 归档记忆} 两 header
 * 分区（003 init 已建模板；文件缺失时本类按同款模板自愈重建）。
 *
 * <p>行为契约：append 按 scope 写对应区块（条目带日期前缀）；load 每次 {@code Files.readString} 重读（坑十五）、核心区完整返回、 归档区超
 * {@value #MAX_ARCHIVE_CHARS} 字只保留最近部分（坑十六：截断函数只接收归档段——核心区物理上动不到）；recallByKeyword 只在归档区做朴素 contains
 * 行匹配（坑十八）。
 *
 * <p><strong>并发与原子写约定（FR-3，自审 #1/#4）</strong>：append 双层互斥——进程内 {@code synchronized} + 跨进程 {@code
 * FileChannel.lock()}（锁文件为 MEMORY.md 同目录的 {@code MEMORY.md.lock}，记忆文件本身会被原子替换、锁在它上面会失效）+
 * <strong>锁内重读</strong>（读-改-写全程互斥，不基于旧内容覆盖）；写回用临时文件（UUID 名）+ {@code Files.move(ATOMIC_MOVE,
 * REPLACE_EXISTING)} 同盘原子替换——任何时刻磁盘上要么旧文件要么新文件，这是 load 免锁的正确性前提。append 失败异常上抛（不静默 "已记住"），由
 * ToolExecutor 审计 success=false。
 *
 * <p>后端故障（文件不可读）异常上抛不静默（FR-6 快速失败：不静默返回空记忆、不自动降级换档）。纯类交付无组件注解（G4-C1 延续），装配处显式 {@code @Bean}。
 */
public class MarkdownMemoryStore implements LongTermMemoryStore {

  private static final Logger LOG = LoggerFactory.getLogger(MarkdownMemoryStore.class);

  /** 核心记忆区 header——截断物理上动不到这一区（坑十六）。 */
  static final String CORE_HEADER = "## 核心记忆";

  /** 归档记忆区 header——超阈值只裁这一区（坑十六）。 */
  static final String ARCHIVE_HEADER = "## 归档记忆";

  /** 归档区截断阈值（字符数）——只管归档区，核心区永不截断。 */
  private static final int MAX_ARCHIVE_CHARS = 4000;

  /** 003 init 同款模板（三行）；文件缺失时自愈重建。 */
  private static final String TEMPLATE =
      "# 长期记忆"
          + System.lineSeparator()
          + CORE_HEADER
          + System.lineSeparator()
          + ARCHIVE_HEADER
          + System.lineSeparator();

  private final Path memoryFile;

  /** 跨进程互斥的锁文件：记忆文件会被 ATOMIC_MOVE 替换，锁只能挂在同目录的伴生文件上。 */
  private final Path lockFile;

  public MarkdownMemoryStore(Path memoryFile) {
    this.memoryFile = memoryFile;
    this.lockFile = memoryFile.resolveSibling(memoryFile.getFileName() + ".lock");
  }

  @Override
  public synchronized void append(String content, MemoryScope scope) {
    // 双层互斥：进程内 synchronized（本方法）+ 跨进程 FileChannel.lock（锁文件）。
    try (FileChannel channel =
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock ignored = channel.lock()) {
      String current = readExisting(); // 锁内重读：读-改-写全程互斥，不基于旧内容覆盖
      if (current.isEmpty()) {
        current = TEMPLATE;
      }
      String header = scope == MemoryScope.CORE ? CORE_HEADER : ARCHIVE_HEADER;
      String entry = "- [" + LocalDate.now(ZoneId.systemDefault()) + "] " + content;
      writeAtomic(insertUnder(current, header, entry));
    } catch (IOException e) {
      // 锁文件不可用/写入失败：快速失败上抛，由 ToolExecutor 审计 success=false
      throw new IllegalStateException("写入记忆文件失败: " + memoryFile, e);
    }
  }

  @Override
  public String load() {
    String raw = readExisting(); // 坑十五：每次重新读，不缓存
    if (raw.isEmpty()) {
      return "";
    }
    String core = extractSection(raw, CORE_HEADER);
    String archive = truncateIfNeeded(extractSection(raw, ARCHIVE_HEADER)); // 坑十六：只裁归档段
    return CORE_HEADER
        + System.lineSeparator()
        + core
        + System.lineSeparator()
        + ARCHIVE_HEADER
        + System.lineSeparator()
        + archive;
  }

  @Override
  public List<String> recallByKeyword(String keyword) {
    String raw = readExisting(); // 坑十五：每次重新读，不缓存
    if (raw.isEmpty() || keyword == null || keyword.isBlank()) {
      return List.of();
    }
    // 坑十八：只搜归档区，朴素 contains 行匹配
    return extractSection(raw, ARCHIVE_HEADER)
        .lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty() && line.contains(keyword))
        .toList();
  }

  /** 每次读文件，文件不存在 = 合法空态（首次使用），存在但读不了 = 后端故障上抛（FR-6）。 */
  private String readExisting() {
    if (!Files.exists(memoryFile)) {
      return "";
    }
    try {
      return Files.readString(memoryFile, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取记忆文件失败: " + memoryFile, e);
    }
  }

  /**
   * 原子写：临时文件（UUID 名）+ {@code Files.move(ATOMIC_MOVE, REPLACE_EXISTING)} 同盘原子替换——任何时刻磁盘上要么旧文件要么
   * 新文件，不存在半写状态（load 免锁的正确性前提）。失败异常上抛，临时文件尽力清理（清理失败仅 WARN 不吞）。
   */
  private void writeAtomic(String content) {
    Path temp = memoryFile.resolveSibling(memoryFile.getFileName() + ".tmp-" + UUID.randomUUID());
    try {
      Files.writeString(temp, content, StandardCharsets.UTF_8);
      Files.move(
          temp, memoryFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      try {
        Files.deleteIfExists(temp);
      } catch (IOException cleanupFailure) {
        // 路径不进日志参数（CRLF 注入防线，CliAgentConfiguration.sanitize 同款口径——此处干脆不带值）
        LOG.warn("临时记忆文件清理失败", cleanupFailure); // 清理失败留痕不吞
      }
      throw new IllegalStateException("写入记忆文件失败: " + memoryFile, e);
    }
  }

  /** 把条目插到指定 header 区块末尾（下一 header 之前）——归档区条目按时间自然倒序保持"最新在尾部"。 */
  private String insertUnder(String current, String header, String entry) {
    int headerIndex = current.indexOf(header);
    if (headerIndex < 0) {
      throw new IllegalStateException("记忆文件缺少分区 header: " + header);
    }
    int nextSection = current.indexOf("## ", headerIndex + header.length());
    int end = nextSection < 0 ? current.length() : nextSection;
    return current.substring(0, end) + entry + System.lineSeparator() + current.substring(end);
  }

  /** 取指定 header 区块的正文（不含 header 行本身、不含下一区块）。 */
  private String extractSection(String raw, String header) {
    int start = raw.indexOf(header);
    if (start < 0) {
      return "";
    }
    int lineEnd = raw.indexOf('\n', start);
    if (lineEnd < 0) {
      return "";
    }
    int next = raw.indexOf("## ", lineEnd + 1);
    String section = next < 0 ? raw.substring(lineEnd + 1) : raw.substring(lineEnd + 1, next);
    return section.trim();
  }

  /**
   * 坑十六：截断只接收归档段（调用点保证——核心区从不经本方法），超阈值保留最近部分（最新条目在尾部，裁头部）。"只保留最近"语义由最值钱回归测试 钉死：含"归档流水 499"、不含"归档流水
   * 0"。
   */
  private String truncateIfNeeded(String archive) {
    if (archive.length() <= MAX_ARCHIVE_CHARS) {
      return archive;
    }
    return archive.substring(archive.length() - MAX_ARCHIVE_CHARS);
  }
}
