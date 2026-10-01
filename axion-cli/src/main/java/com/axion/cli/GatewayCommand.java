package com.oryxos.cli;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;

/**
 * gateway 命令（重命令，启动 Spring）——占位：多通道挂载归后续节（课件 §三"gateway 起守护进程挂多个通道"）， 本课启动 Spring
 * 后输出占位提示并正常退出（用户拍板口径）。
 */
@Command(name = "gateway", description = "启动多渠道守护进程（多通道挂载归后续节）", mixinStandardHelpOptions = true)
public class GatewayCommand implements Runnable {

  @Override
  public void run() {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(applicationClass())
            .web(WebApplicationType.NONE) // 占位阶段不起 Web 容器（多通道挂载归后续节）
            .headless(true)
            .run()) {
      System.out.println("gateway 的多通道挂载归后续节，当前为占位");
    }
  }

  private Class<?> applicationClass() {
    try {
      return Class.forName("com.oryxos.boot.OryxOsApplication");
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("未找到 Spring 启动类 com.oryxos.boot.OryxOsApplication", e);
    }
  }
}
