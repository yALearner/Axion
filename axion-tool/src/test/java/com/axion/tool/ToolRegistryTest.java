package com.oryxos.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.oryxos.core.OryxTool;
import com.oryxos.core.Profile;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ToolRegistry 验收 harness——三种来源统一注册（来源无感知）；坑十四：按 Profile.tools 过滤子集"不多不少" （多一个 = 没过滤干净、少一个 =
 * 过滤过头，都是错）；重名注册拒绝 + WARN（不静默覆盖）；未知名工具明确报错 （FR-1 自审补钉，001 provider 引用校验同款纪律）。
 */
class ToolRegistryTest {

  private final ToolRegistry registry = new ToolRegistry();

  @Test
  @DisplayName("三来源工具统一以 OryxTool 身份注册：contains/all 正确")
  void registersToolsFromAllSources() {
    registry.register(tool("read_file"));
    registry.register(tool("github_pr_list")); // 方式二 MCP 来源
    registry.register(tool("echo")); // 方式三 @Tool 来源

    assertThat(registry.contains("read_file")).isTrue();
    assertThat(registry.contains("no_such")).isFalse();
    assertThat(registry.all())
        .extracting(OryxTool::getName)
        .containsExactlyInAnyOrder("read_file", "github_pr_list", "echo");
  }

  @Test
  @DisplayName("坑十四：过滤子集恰好等于声明列表——多一个和少一个都断言失败")
  void filterReturnsExactSubset() {
    registry.register(tool("read_file"));
    registry.register(tool("write_file"));
    registry.register(tool("notify"));
    Profile profile = profileWithTools("read_file", "notify");

    List<OryxTool> filtered = registry.filter(profile);

    assertThat(filtered)
        .extracting(OryxTool::getName)
        .containsExactlyInAnyOrder("read_file", "notify");
    assertThat(filtered).hasSize(2); // 少一个（过滤过头）也是错
  }

  @Test
  @DisplayName("重名注册：明确拒绝不静默覆盖（保留先注册者）")
  void duplicateNameRejected() {
    OryxTool first = tool("read_file");
    OryxTool shadowing = tool("read_file");
    registry.register(first);

    registry.register(shadowing);

    assertThat(registry.contains("read_file")).isTrue();
    // 不静默覆盖：同名后注册者不遮蔽先注册者
    assertThat(registry.all().stream().filter(t -> t.getName().equals("read_file")).findFirst())
        .containsSame(first);
  }

  @Test
  @DisplayName("未知名工具：filter 明确报错，不静默少一个（FR-1）")
  void unknownToolNameThrows() {
    registry.register(tool("read_file"));
    Profile profile = profileWithTools("read_file", "typo_tool");

    assertThatThrownBy(() -> registry.filter(profile))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("typo_tool");
  }

  @Test
  @DisplayName("空声明：过滤返回空列表（不报错）")
  void emptyDeclarationReturnsEmpty() {
    registry.register(tool("read_file"));

    assertThat(registry.filter(profileWithTools())).isEmpty();
  }

  private OryxTool tool(String name) {
    OryxTool tool = mock(OryxTool.class);
    when(tool.getName()).thenReturn(name);
    return tool;
  }

  private Profile profileWithTools(String... names) {
    Profile profile = mock(Profile.class);
    when(profile.tools()).thenReturn(names.length == 0 ? List.of() : List.of(names));
    return profile;
  }
}
