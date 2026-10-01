package com.oryxos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.core.MemoryScope;
import com.oryxos.core.MemoryService;
import com.oryxos.core.ToolExecutor;
import com.oryxos.core.ToolResult;
import com.oryxos.storage.ToolInvocation;
import com.oryxos.storage.ToolInvocationRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;

/** 两记忆 Tool 的契约 harness（US1）——scope 缺省/非法值、未命中措辞、必填校验 + 审计落账断言（宪法 V 显式任务）。 */
class MemoryToolsTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final MemoryService memoryService = mock(MemoryService.class);
  private final SaveMemoryTool saveMemoryTool = new SaveMemoryTool(memoryService);
  private final RecallMemoryTool recallMemoryTool = new RecallMemoryTool(memoryService);

  private JsonNode json(String content) throws Exception {
    return mapper.readTree(content);
  }

  @Test
  @DisplayName(
      "坑十二同款契约：两 Tool 的 name/description/inputSchema 三件套非空（跨模块不可用 OryxToolContractTest"
          + " 自动纳入，此处等价覆盖）")
  void bothToolsHaveCompleteContract() {
    for (com.oryxos.core.OryxTool tool : List.of(saveMemoryTool, recallMemoryTool)) {
      assertThat(tool.getName()).isNotBlank();
      assertThat(tool.getDescription()).isNotBlank();
      assertThat(tool.getInputSchema()).isNotNull(); // 缺了它，Provider 翻译 Function Calling 时直接卡死
    }
  }

  @Test
  @DisplayName("坑十七：scope 缺省写 ARCHIVAL——工具层不猜")
  void scopeDefaultsToArchival() throws Exception {
    ToolResult result = saveMemoryTool.execute(json("{\"content\":\"项目用 Spring Boot\"}"));

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("已记住");
    verify(memoryService).remember("项目用 Spring Boot", MemoryScope.ARCHIVAL);
  }

  @Test
  @DisplayName("坑十七：scope 显式 core / archival 路由正确")
  void explicitScopeRoutes() throws Exception {
    saveMemoryTool.execute(json("{\"content\":\"A\",\"scope\":\"core\"}"));
    saveMemoryTool.execute(json("{\"content\":\"B\",\"scope\":\"archival\"}"));

    verify(memoryService).remember("A", MemoryScope.CORE);
    verify(memoryService).remember("B", MemoryScope.ARCHIVAL);
  }

  @Test
  @DisplayName("坑十七：scope 非法值明确报错、不写记忆")
  void illegalScopeFailsLoudly() throws Exception {
    ToolResult result = saveMemoryTool.execute(json("{\"content\":\"C\",\"scope\":\"bogus\"}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("scope");
    verify(memoryService, never())
        .remember(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("content 必填校验：缺失或空白明确报错（005 S1 口径）")
  void contentRequired() throws Exception {
    ToolResult missing = saveMemoryTool.execute(json("{}"));
    ToolResult blank = saveMemoryTool.execute(json("{\"content\":\"  \"}"));

    assertThat(missing.success()).isFalse();
    assertThat(missing.errorMessage()).contains("content");
    assertThat(blank.success()).isFalse();
    verify(memoryService, never())
        .remember(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("RecallMemory：命中换行拼接")
  void recallHitJoinsLines() throws Exception {
    when(memoryService.recall("偏好")).thenReturn(List.of("条目一", "条目二"));

    ToolResult result = recallMemoryTool.execute(json("{\"keyword\":\"偏好\"}"));

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("条目一\n条目二");
  }

  @Test
  @DisplayName("RecallMemory：未命中返回「没有找到相关记忆」不抛异常")
  void recallMissReturnsFriendlyMessage() throws Exception {
    when(memoryService.recall("不存在")).thenReturn(List.of());

    ToolResult result = recallMemoryTool.execute(json("{\"keyword\":\"不存在\"}"));

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("没有找到相关记忆");
  }

  @Test
  @DisplayName("keyword 必填校验：缺失或空白明确报错")
  void keywordRequired() throws Exception {
    ToolResult missing = recallMemoryTool.execute(json("{}"));

    assertThat(missing.success()).isFalse();
    assertThat(missing.errorMessage()).contains("keyword");
    verify(memoryService, never()).recall(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  @DisplayName("宪法 V：save_memory/recall_memory 经 ToolExecutor 执行后 tool_invocations 落账 success=true")
  void auditWrittenOnToolExecution() throws Exception {
    ToolInvocationRepository auditRepo = mock(ToolInvocationRepository.class);
    when(memoryService.recall("x")).thenReturn(List.of());
    ToolExecutor executor =
        new ToolExecutor(
            Map.of("save_memory", saveMemoryTool, "recall_memory", recallMemoryTool),
            auditRepo,
            new ObjectMapper());

    ToolResult saved =
        executor.execute(
            "sess-1",
            new AssistantMessage.ToolCall(
                "call-1", "function", "save_memory", "{\"content\":\"偏好 Java\"}"));
    ToolResult recalled =
        executor.execute(
            "sess-1",
            new AssistantMessage.ToolCall(
                "call-2", "function", "recall_memory", "{\"keyword\":\"x\"}"));

    assertThat(saved.success()).isTrue();
    assertThat(recalled.success()).isTrue();
    ArgumentCaptor<ToolInvocation> captor = ArgumentCaptor.forClass(ToolInvocation.class);
    verify(auditRepo, times(2)).save(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(ToolInvocation::getToolName)
        .containsExactlyInAnyOrder("save_memory", "recall_memory");
    assertThat(captor.getAllValues()).allSatisfy(r -> assertThat(r.getSuccess()).isTrue());
  }

  @Test
  @DisplayName("宪法 V：执行失败同样落账 success=false（不静默）")
  void auditWrittenOnFailure() throws Exception {
    ToolInvocationRepository auditRepo = mock(ToolInvocationRepository.class);
    ToolExecutor executor =
        new ToolExecutor(Map.of("save_memory", saveMemoryTool), auditRepo, new ObjectMapper());

    ToolResult result =
        executor.execute(
            "sess-1",
            new AssistantMessage.ToolCall(
                "call-1", "function", "save_memory", "{\"content\":\"D\",\"scope\":\"bogus\"}"));

    assertThat(result.success()).isFalse();
    ArgumentCaptor<ToolInvocation> captor = ArgumentCaptor.forClass(ToolInvocation.class);
    verify(auditRepo).save(captor.capture());
    assertThat(captor.getValue().getSuccess()).isFalse();
    assertThat(captor.getValue().getErrorMessage()).isNotNull();
  }
}
