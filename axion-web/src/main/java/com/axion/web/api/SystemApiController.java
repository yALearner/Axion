package com.oryxos.web.api;

import com.oryxos.provider.ProviderProperties;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 系统状态端点（009-web-service FR-1）——/health 回 ok；/info 返回运行信息 + 各 Provider 注册状态。 */
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "Controller 为薄壳（校验/包装/错误三件事），只读端点无认证属核心阶段明确不做（内网假设），业务逻辑与审计在核心层——FindSecBugs 攻击面提示与宪法"
            + " II/VIII 设计一致")
@RestController
@RequestMapping("/api/v1")
public class SystemApiController {

  private final Environment environment;

  public SystemApiController(Environment environment) {
    this.environment = environment;
  }

  @GetMapping("/health")
  public ApiResponse<Map<String, String>> health() {
    return ApiResponse.ok(Map.of("status", "ok"));
  }

  /**
   * 运行信息：Provider 列表（**脱敏**——不输出 apiKey 凭证，只给 name/baseUrl/model）+ 状态「已注册」。
   * 实现级明确：核心阶段不做真连通探测（探测/指标归扩展阶段 Prometheus 一并做）。
   */
  @GetMapping("/info")
  public ApiResponse<InfoResponse> info() {
    List<ProviderProperties> providers =
        Binder.get(environment)
            .bind("oryxos.providers", Bindable.listOf(ProviderProperties.class))
            .orElse(List.of());
    List<ProviderStatus> statuses =
        providers.stream()
            .map(p -> new ProviderStatus(p.getName(), p.getBaseUrl(), p.getModel(), "registered"))
            .toList();
    return ApiResponse.ok(new InfoResponse(statuses));
  }

  /** 运行信息。 */
  public record InfoResponse(List<ProviderStatus> providers) {
    public InfoResponse {
      // 防御性拷贝（007 先例）：不暴露可变列表的内部表示——Jackson 序列化读 accessor 不受影响
      providers = providers == null ? java.util.List.of() : java.util.List.copyOf(providers);
    }
  }

  /** Provider 注册状态（脱敏：无 apiKey）。 */
  public record ProviderStatus(String name, String baseUrl, String model, String status) {}
}
