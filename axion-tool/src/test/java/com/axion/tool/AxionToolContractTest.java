package com.oryxos.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.oryxos.core.OryxTool;
import com.oryxos.tool.builtin.HttpGetTool;
import com.oryxos.tool.builtin.HttpPostTool;
import com.oryxos.tool.builtin.ListDirTool;
import com.oryxos.tool.builtin.NotifyTools;
import com.oryxos.tool.builtin.ReadFileTool;
import com.oryxos.tool.builtin.ShellTools;
import com.oryxos.tool.builtin.WriteFileTool;
import com.oryxos.tool.notify.NotifyChannelRegistry;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.RestClient;

/**
 * 坑十二：工具契约三件套参数化测试（课件 §四最值钱测试之一）——遍历 ToolRegistry 每个工具断言 name/description/inputSchema 非空：任何一个工具漏实现
 * getInputSchema()，Provider 翻译 Function Calling 时直接卡死，这里立刻红。新工具自动纳入（注册进下方 registry 即被检查）。
 */
class OryxToolContractTest {

  @ParameterizedTest(name = "契约三件套：{0}")
  @MethodSource("allRegisteredTools")
  @DisplayName("每个工具的契约三件套都不能缺（坑十二）")
  void everyToolHasCompleteContract(OryxTool tool) {
    assertThat(tool.getName()).isNotBlank();
    assertThat(tool.getDescription()).isNotBlank();
    assertThat(tool.getInputSchema()).isNotNull(); // 缺了它，Provider 翻译 Function Calling 时直接卡死
  }

  /** 契约样本注册表：真实工具全部注册——新工具在此注册即自动纳入坑十二检查。 */
  private static Stream<OryxTool> allRegisteredTools() {
    Sandbox sandbox = mock(Sandbox.class);
    RestClient restClient = RestClient.builder().build();
    ToolRegistry registry = new ToolRegistry();
    registry.register(new ReadFileTool(sandbox));
    registry.register(new WriteFileTool(sandbox));
    registry.register(new ListDirTool(sandbox));
    registry.register(new ShellTools(sandbox, 30_000, Set.of(), Path.of(".")));
    registry.register(new HttpGetTool(sandbox, restClient));
    registry.register(new HttpPostTool(sandbox, restClient));
    registry.register(
        new NotifyTools(
            sandbox,
            Map.of("webhook", mock(com.oryxos.tool.notify.NotifyChannelAdapter.class)),
            mock(NotifyChannelRegistry.class)));
    return registry.all().stream();
  }
}
