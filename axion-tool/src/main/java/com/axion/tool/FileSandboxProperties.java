package com.oryxos.tool;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件白名单配置（{@code file.allowed_paths}，007-sandbox FR-2）。
 *
 * <p>纯数据 record——按 G4-C1 无组件注解，由装配处（{@code CliAgentConfiguration}）经
 * {@code @EnableConfigurationProperties} 注册后注入 {@link WhitelistSandbox}。
 *
 * <p>空 = 什么都不允许（fail-closed，"不校验"口径的回归钉）——空列表经 {@code Set.copyOf}/{@code List.copyOf}
 * 天然全拒；相对根按启动目录解析（构造期 toAbsolutePath，修订说明 ⑦a）。
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "record 访问器为 Spring Boot 构造器绑定契约要求；实际暴露风险为零——WhitelistSandbox 构造期"
            + " List.copyOf 拷贝后不再保留本对象引用（004/006 EI 抑制同款先例）")
@ConfigurationProperties(prefix = "file")
public record FileSandboxProperties(List<String> allowedPaths) {

  public FileSandboxProperties {
    // 构造器绑定缺失配置键时给 null——归一为空列表：空 = 什么都不允许（fail-closed），不得 NPE
    if (allowedPaths == null) {
      allowedPaths = List.of();
    }
  }
}
