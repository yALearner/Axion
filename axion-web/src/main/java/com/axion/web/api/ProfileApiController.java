package com.axion.web.api;

import com.axion.core.ProfileRegistry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Profile 查询端点（009-web-service FR-1）——列出所有已加载的 Profile（空表返回空列表）。 */
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "Controller 为薄壳（校验/包装/错误三件事），只读端点无认证属核心阶段明确不做（内网假设），业务逻辑与审计在核心层——FindSecBugs 攻击面提示与宪法"
            + " II/VIII 设计一致")
@RestController
@RequestMapping("/api/v1/profiles")
public class ProfileApiController {

  private final ProfileRegistry profileRegistry;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "ProfileRegistry 为装配处注入的单例（只读使用、不暴露引用）——AgentScheduler 同款先例")
  public ProfileApiController(ProfileRegistry profileRegistry) {
    this.profileRegistry = profileRegistry;
  }

  @GetMapping
  public ApiResponse<List<ProfileSummary>> list() {
    List<ProfileSummary> profiles =
        profileRegistry.list().stream()
            .map(
                p ->
                    new ProfileSummary(
                        p.name(),
                        p.description(),
                        p.identity() == null ? null : p.identity().agentName()))
            .toList();
    return ApiResponse.ok(profiles);
  }

  /** Profile 摘要（不暴露 provider 凭证等内部细节）。 */
  public record ProfileSummary(String name, String description, String agentName) {}
}
