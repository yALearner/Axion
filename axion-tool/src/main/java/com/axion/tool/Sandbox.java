package com.oryxos.tool;

/**
 * 沙箱校验接口（接口墙，宪法 VI）。
 *
 * <p>只有一个方法，表达"在受控环境里执行一个动作"这个意图；**不携带任何实现细节**——签名里不出现"白名单""容器镜像""VM 配置"这类某一档实现特有的词（用最重的 microVM
 * 实现反向套此签名也应能干净套入）。
 *
 * <p>本节状态（002-react FR-7）：纯接口、零实现、无人调用——涉外 IO 的 enforce 由各工具在 {@code execute} 首行 自行接入（第 20
 * 节起），{@code WhitelistSandbox} 实现与三层白名单归第 23/24 节；扩展阶段换容器/microVM 只新增 实现类，接口不变。违规审计复用 ToolExecutor
 * 既有失败路径，不新增审计逻辑（技术方案 §6.7）。
 */
public interface Sandbox {

  /** 校验一个涉外动作；校验失败抛 {@link SandboxViolationException}，通过则正常返回。 */
  void enforce(SandboxAction action);
}
