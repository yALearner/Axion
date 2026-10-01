package com.oryxos.cli;

import com.oryxos.channel.cli.CliChannel;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * chat 命令（重命令，启动 Spring）——课件第 18 节 §三骨架：读—转交—打印，交给 {@link CliChannel} 执行交互循环。
 *
 * <p>启动类按名加载 {@code com.oryxos.boot.OryxOsApplication}（坑九防线所在）：oryxos-cli 与 oryxos-boot 是
 * 编译期反向依赖（boot 依赖 cli），故用 {@link Class#forName} 打破编译期循环——运行时 fat jar 内 boot 类必然在 classpath。
 */
@Command(name = "chat", description = "在终端里和 Agent 交互式对话", mixinStandardHelpOptions = true)
public class ChatCommand implements Runnable {

  @Option(names = "--profile", defaultValue = "default", description = "Agent 名（默认 default）")
  String profileName;

  @Option(names = "--message", description = "发单条消息后退出")
  String message;

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
            .web(WebApplicationType.NONE) // CLI 重命令不起 Web 容器（serve 归第 26 节，8080 不抢）
            .headless(true)
            .run()) {
      CliChannel channel = context.getBean(CliChannel.class);
      channel.chat(profileName, message);
    }
  }
}
