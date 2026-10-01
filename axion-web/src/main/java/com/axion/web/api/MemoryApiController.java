package com.oryxos.web.api;

import com.oryxos.core.LongTermMemoryStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 长期记忆查询端点（009-web-service FR-1）——返回 MEMORY.md 原文。
 *
 * <p>实现级明确：直连 core 的 {@link LongTermMemoryStore#load()}——不扩 MemoryService 门面（门面只管 ReAct
 * 上下文三件事），运维查询 直连 store；未来门面扩展时再收口。
 */
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "Controller 为薄壳（校验/包装/错误三件事），只读端点无认证属核心阶段明确不做（内网假设），业务逻辑与审计在核心层——FindSecBugs 攻击面提示与宪法"
            + " II/VIII 设计一致")
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryApiController {

  private final LongTermMemoryStore longTermMemoryStore;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "LongTermMemoryStore 为装配处注入的单例（只读使用、不暴露引用），004 WebhookNotifyAdapter 同款先例")
  public MemoryApiController(LongTermMemoryStore longTermMemoryStore) {
    this.longTermMemoryStore = longTermMemoryStore;
  }

  @GetMapping
  public ApiResponse<MemoryResponse> load() {
    return ApiResponse.ok(new MemoryResponse(longTermMemoryStore.load()));
  }

  /** 长期记忆响应。 */
  public record MemoryResponse(String content) {}
}
