package com.oryxos.cli;

import com.oryxos.core.OryxTool;
import com.oryxos.tool.ToolRegistry;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;

/**
 * tool list 命令（005-tool 自轻命令改造为重命令——启动 Spring 读 ToolRegistry 全量列表）。
 *
 * <p>003 时为占位轻命令（"内置工具尚未就位"）；005 工具体系就位后，真实工具列表只在 Spring 容器里（ToolRegistry bean），故改为重命令（ChatCommand
 * 同款启动模式：{@code Class#forName} 打破 cli/boot 编译期循环 + web(NONE) 不抢 8080）。输出格式：工具名 + 描述，供人工验收"tool list
 * 可见全部注册工具"。
 */
@Command(name = "list", description = "列出已注册的 Tool", mixinStandardHelpOptions = true)
public class ToolListCommand implements Runnable {

  @Override
  public void run() {
    Class<?> applicationClass;
    try {
      applicationClass = Class.forName("com.oryxos.boot.OryxOsApplication");
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("未找到 Spring 启动类 com.oryxos.boot.OryxOsApplication", e);
    }
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(applicationClass)
            .web(WebApplicationType.NONE)
            .headless(true)
            .run()) {
      ToolRegistry registry = context.getBean(ToolRegistry.class);
      for (OryxTool tool : registry.all()) {
        System.out.println(tool.getName() + "\t" + tool.getDescription());
      }
    }
  }
}
