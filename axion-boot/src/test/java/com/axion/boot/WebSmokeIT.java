package com.oryxos.boot;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WebSmokeIT（009-web-service FR 验收，课件 §四）——@SpringBootTest 起真实上下文（不依赖模型），验证 /health /info /profiles
 * /tools 真实链路可达：Bean 装配和扫描范围没炸；**JPA repository 扫描红线**——18 节「Found 0 repositories」坑在 web 模块复发时
 * 这里第一时间红，不用等到手动 serve。
 */
@Tag("integration")
@SpringBootTest(classes = OryxOsApplication.class)
@AutoConfigureMockMvc
class WebSmokeIT {

  static {
    // 坑八：surefire 工作目录 = 模块目录，相对路径数据源落 oryxos-boot/.oryxos/oryxos.db——父目录先建
    try {
      Files.createDirectories(Path.of(".oryxos"));
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void providerProperties(DynamicPropertyRegistry registry) {
    // 坑表：索引式覆盖必须补全整元素字段（008 实录）
    registry.add("oryxos.providers[0].name", () -> "deepseek");
    registry.add("oryxos.providers[0].api-key", () -> "dummy");
    registry.add("oryxos.providers[0].base-url", () -> "http://127.0.0.1:9");
    registry.add("oryxos.providers[1].name", () -> "kimi");
    registry.add("oryxos.providers[1].api-key", () -> "dummy");
    registry.add("oryxos.providers[1].base-url", () -> "http://127.0.0.1:9");
  }

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("真上下文：/health /info /profiles /tools 真实可达（Bean 装配 + JPA 扫描红线）")
  void coreEndpointsReachableInRealContext() throws Exception {
    mockMvc
        .perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.status").value("ok"));

    mockMvc
        .perform(get("/api/v1/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.providers").isArray());

    mockMvc
        .perform(get("/api/v1/profiles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    mockMvc
        .perform(get("/api/v1/tools"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data").isArray());
  }
}
