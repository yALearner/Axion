package com.oryxos.tool.notify;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 核心阶段唯一实现（FR-3）：对 {@link NotifyTarget#config()} 里的 {@code url} 发通用 webhook POST。
 *
 * <p>body 为通用 text 格式 {@code {"msgtype":"text","text":{"content":...}}}——**企业微信与钉钉共用此形态**
 * （2026-09-05 人工验收 40008 实锤修正：课件骨架的 {@code {"content":...}} 被企业微信判 invalid message type）； 飞书 text
 * 格式不同（msg_type/content/text），归扩展阶段专用 Adapter。签名算法、AccessToken 刷新、body errcode 解析均留扩展阶段。
 *
 * <p>坑十一：webhook 返回 4xx/5xx 或网络失败时异常原样上抛、不静默吞掉——吞掉 = Agent 以为发出去了； 错误路径由 ToolExecutor 既有审计承接。URL 只从
 * config 取、不硬编码。
 *
 * <p>不加 {@code @Component}（G4-C1 钉死）：boot 扫描 com.oryxos 全树，RestClient bean 由第 20 节 装配处用 Boot 自动配置的
 * {@code RestClient.Builder} 构建（必设 connect/read timeout）后显式 {@code @Bean}。
 */
public class WebhookNotifyAdapter implements NotifyChannelAdapter {

  private final RestClient restClient;

  /** 装配处用 RestClient.Builder 构建并设 connect/read timeout 后注入（实现级明确）。 */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "RestClient 为装配处注入的 Spring 单例（协议栈不可复制），仅本类只读使用、不暴露引用；"
              + "装配与 timeout 归第 20 节装配处，此处不持有可变状态（001-provider 抑制注解先例）")
  public WebhookNotifyAdapter(RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.url(); // E1/S4：经访问器取用，键名不散落、缺失或空串明确报错
    restClient
        .post()
        .uri(URI.create(url))
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("msgtype", "text", "text", Map.of("content", content)))
        .retrieve()
        .toBodilessEntity(); // 异常原样上抛，不 catch 不吞
  }
}
