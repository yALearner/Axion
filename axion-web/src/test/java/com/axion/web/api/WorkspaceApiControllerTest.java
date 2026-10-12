package com.axion.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axion.core.AgentLifecycleService;
import com.axion.core.AgentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * WorkspaceApiController harness（012 FR-6/FR-7）——tree 结构、file 读、坑十二穿越 400（关键回归）、写盘、坑十三 AGENT.md 走
 * update。
 */
class WorkspaceApiControllerTest {

  @TempDir Path workspace;

  private final AgentLifecycleService lifecycle = mock(AgentLifecycleService.class);

  private MockMvc mockMvc(AgentStore store) {
    return MockMvcBuilders.standaloneSetup(new WorkspaceApiController(store, lifecycle))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  @Test
  @DisplayName("tree：返回 agents/archive 结构、可钻进 Agent 目录列文件")
  void treeReturnsAgentsAndArchiveStructure() throws Exception {
    AgentStore store = new AgentStore(workspace);
    store.write("ops-agent", "---\nname: ops-agent\n---\n正文");
    store.write("weather", "---\nname: weather\n---\n正文");

    mockMvc(store)
        .perform(get("/api/v1/workspace/tree"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value(".axion"))
        .andExpect(jsonPath("$.data.children[0].name").value("agents"))
        .andExpect(jsonPath("$.data.children[0].children.length()").value(2))
        .andExpect(jsonPath("$.data.children[0].children[0].children[0].name").value("AGENT.md"));
  }

  @Test
  @DisplayName("file 读：正常文件返回内容")
  void fileReadsContent() throws Exception {
    AgentStore store = new AgentStore(workspace);
    store.write("ops-agent", "正文内容");

    mockMvc(store)
        .perform(get("/api/v1/workspace/file").param("path", "agents/ops-agent/AGENT.md"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.containsString("正文内容")));
  }

  @Test
  @DisplayName("坑十二关键回归：file?path=../../etc/passwd 目录穿越 → 400")
  void pathTraversalIsRejected() throws Exception {
    AgentStore store = new AgentStore(workspace);

    mockMvc(store)
        .perform(get("/api/v1/workspace/file").param("path", "../../etc/passwd"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("越界")));
  }

  @Test
  @DisplayName("坑十二：写盘同款防穿越（POST /workspace/file 越界 400）")
  void writePathTraversalIsRejected() throws Exception {
    AgentStore store = new AgentStore(workspace);

    mockMvc(store)
        .perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"../evil.txt\",\"content\":\"x\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("写盘：普通文件直接写落对位置")
  void writeFileLandsOnDisk() throws Exception {
    AgentStore store = new AgentStore(workspace);
    store.write("ops-agent", "---\nname: ops-agent\n---\n正文");

    mockMvc(store)
        .perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"agents/ops-agent/REFERENCE.md\",\"content\":\"参考内容\"}"))
        .andExpect(status().isOk());

    assertThat(
            Files.readString(
                workspace.resolve("agents").resolve("ops-agent").resolve("REFERENCE.md")))
        .isEqualTo("参考内容");
  }

  @Test
  @DisplayName("坑十三：编辑 agents/<name>/AGENT.md 显式走 update 重注册（macOS 子目录不监听的兜底）")
  void editingAgentMarkdownGoesThroughUpdate() throws Exception {
    AgentStore store = new AgentStore(workspace);
    store.write("ops-agent", "---\nname: ops-agent\n---\n旧正文");

    mockMvc(store)
        .perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\"agents/ops-agent/AGENT.md\",\"content\":\"---\\n"
                        + "name: ops-agent\\n"
                        + "---\\n"
                        + "新正文\"}"))
        .andExpect(status().isOk());

    verify(lifecycle).update(eq("ops-agent"), anyString()); // 走 update 重注册，不是裸写盘
    assertThat(
            Files.readString(workspace.resolve("agents").resolve("ops-agent").resolve("AGENT.md")))
        .doesNotContain("新正文"); // 写盘权在 lifecycle（mock 未真正写——行为等价由 lifecycle 单测钉死）
  }

  @Test
  @DisplayName("file 读：不存在文件 → 400（可读原因）")
  void fileMissingYields400() throws Exception {
    AgentStore store = new AgentStore(workspace);

    mockMvc(store)
        .perform(get("/api/v1/workspace/file").param("path", "agents/nothing/AGENT.md"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不存在")));
  }
}
