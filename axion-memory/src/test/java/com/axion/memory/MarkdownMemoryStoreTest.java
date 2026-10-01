package com.oryxos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oryxos.core.MemoryScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MarkdownMemoryStore 基础契约 harness（US1）——坑十五/十七/十八。截断回归（坑十六）、并发回归与 FR-6 故障路径在 US2 增补（T018/T019）。
 */
class MarkdownMemoryStoreTest {

  private MarkdownMemoryStore store;

  @BeforeEach
  void setUp(@TempDir Path tempDir) {
    store = new MarkdownMemoryStore(tempDir.resolve("MEMORY.md"));
  }

  @Test
  @DisplayName("坑十六最值钱回归：截断只裁归档区、核心记忆一字不能少")
  void truncationOnlyAffectsArchiveSection() {
    store.append("用户叫小王，偏好用 Java", MemoryScope.CORE);
    for (int i = 0; i < 500; i++) {
      store.append("归档流水 " + i, MemoryScope.ARCHIVAL); // 把归档区灌到远超 4000 字
    }

    String loaded = store.load();

    assertThat(loaded).contains("用户叫小王，偏好用 Java"); // 核心区完整——"始终在场"的底线
    assertThat(loaded).doesNotContain("归档流水 0"); // 归档区最早的内容被裁掉了
    assertThat(loaded).contains("归档流水 499"); // 保留的是最近的
  }

  @Test
  @DisplayName("FR-6 故障路径：记忆文件不可读 → append/load 异常上抛不静默（快速失败，不返回空记忆）")
  void unreadableFileFailsFast(@TempDir Path tempDir) throws Exception {
    Path dirAsFile = tempDir.resolve("MEMORY.md");
    Files.createDirectories(dirAsFile); // 目录冒充文件：读必失败
    MarkdownMemoryStore broken = new MarkdownMemoryStore(dirAsFile);

    assertThatThrownBy(broken::load).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> broken.append("写不进去", MemoryScope.ARCHIVAL))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("并发回归：50 虚拟线程各 append 一条 → load 全部命中零丢失（双层互斥 + 原子写回归钉）")
  void concurrentAppendsLoseNothing() throws Exception {
    // 需求文档验收标准明定的并发形态：跨会话虚拟线程并发追加是单实例多 Agent 的真实场景。
    // 仅测试内使用虚拟线程验证并发契约；生产代码保持全程同步（宪法 VII 不因此变更）。
    List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 50; i++) {
        final int index = i;
        futures.add(executor.submit(() -> store.append("并发条目 " + index, MemoryScope.ARCHIVAL)));
      }
    }
    for (java.util.concurrent.Future<?> future : futures) {
      future.get(); // 显式等全部完成并上抛执行异常（不吞）
    }

    String loaded = store.load();
    for (int i = 0; i < 50; i++) {
      assertThat(loaded).contains("并发条目 " + i);
    }
  }

  @Test
  @DisplayName("坑十五：append 后同一实例立刻 load 命中——不缓存")
  void appendImmediatelyVisibleInLoad() {
    store.append("刚记的事", MemoryScope.ARCHIVAL);

    assertThat(store.load()).contains("刚记的事");
  }

  @Test
  @DisplayName("坑十五：append 后 recallByKeyword 同样立即可见——不缓存")
  void appendImmediatelyVisibleInRecall() {
    store.append("刚记的事", MemoryScope.ARCHIVAL);

    assertThat(store.recallByKeyword("刚记的事")).hasSize(1);
    assertThat(store.recallByKeyword("刚记的事").get(0)).contains("刚记的事");
  }

  @Test
  @DisplayName("坑十七：scope 路由正确区块——CORE 进核心区、ARCHIVAL 进归档区")
  void scopeRoutesToCorrectSection() {
    store.append("核心条目", MemoryScope.CORE);
    store.append("归档条目", MemoryScope.ARCHIVAL);

    String loaded = store.load();
    assertThat(loaded).contains("核心条目").contains("归档条目");
    assertThat(loaded.indexOf("核心条目"))
        .isLessThan(loaded.indexOf(MarkdownMemoryStore.ARCHIVE_HEADER));
    assertThat(loaded.indexOf("归档条目"))
        .isGreaterThan(loaded.indexOf(MarkdownMemoryStore.ARCHIVE_HEADER));
  }

  @Test
  @DisplayName("坑十八：recallByKeyword 只搜归档区——核心区同关键词不命中")
  void recallOnlySearchesArchiveSection() {
    store.append("核心区的独有词X", MemoryScope.CORE);
    store.append("归档区的独有词X", MemoryScope.ARCHIVAL);

    assertThat(store.recallByKeyword("独有词X")).hasSize(1);
    assertThat(store.recallByKeyword("独有词X").get(0)).contains("归档区的");
  }

  @Test
  @DisplayName("关键词未命中：返回空列表不抛异常")
  void unmatchedKeywordReturnsEmptyList() {
    store.append("已存在的内容", MemoryScope.ARCHIVAL);

    assertThat(store.recallByKeyword("不存在的关键词")).isEmpty();
  }

  @Test
  @DisplayName("记忆文件不存在（首次使用）：load 返回空、recall 返回空——合法空态不报错")
  void missingFileIsLegalEmptyState() {
    assertThat(store.load()).isEmpty();
    assertThat(store.recallByKeyword("任意")).isEmpty();
  }
}
