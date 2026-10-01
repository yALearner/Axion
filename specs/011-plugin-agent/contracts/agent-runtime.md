# 跨节契约：011-plugin-agent 运行时注册与资源加载

> Phase 1 输出。本节不新增 REST 端点/数据表——本契约定义 30 节（`/api/v1/agents` + WorkspaceWatcher + 一句话生成）与后续节的**调用契约**；后续节改动以下任一视为修改前序公共接口，需停下报告（技术方案 §11.3）。

## 1. AgentLoader（axion-core，30 节直接消费）

```java
public final class AgentLoader {
  public static FrontMatterAndBody split(String content);          // frontmatter（YAML Map）+ 正文（原样）
  public String loadBody(Path agentDir);                            // 现读正文（去 frontmatter）；缺 AGENT.md → 报错点名
  public Set<String> detectResources(Path agentDir);                // {scripts, skills, REFERENCE.md} 中存在的
  public Profile deriveProfile(Path agentDir, Set<String> providerNames);
      // 校验：缺 name/provider 报错点名（坑二）、name=目录名（坑八）、schedules 缺 id（010 ⑦c）、provider 引用（001）
  public Map<String, Profile> loadAll(Path agentsRoot, Set<String> providerNames);  // 坏目录跳过不阻断
}
```

**契约要点**：30 节 API 上传写完目录后调 `deriveProfile` + `register`，与启动扫描**同一段代码、同一异常类型、同一消息**（坑五）；`ProfileLoader` 类名自本节起删除（更名），任何代码不得再引用。

## 2. ProfileRegistry（001 演进）

```java
public void register(Profile profile);   // 覆盖语义（001 既有）
public void remove(String name);         // 幂等注销（30 节 DELETE 前置）
public boolean exists(String name);      // 存在性查询
public Optional<Profile> findByName(String name);
public Collection<Profile> list();
```

## 3. AgentScheduler.registerProfile（008/010 演进）

```java
public void registerAll();               // = profileRegistry.list().forEach(this::registerProfile)
public void registerProfile(Profile profile);
// 语义：id 冲突查 registrations（报错文案同 010："定时任务 id 冲突: X（Profile A 与 B）"）
//      → store.register（含 next_run_at）→ taskScheduler.schedule → scheduledTasks 句柄 + registrations
// 30 节注销/更新前置：scheduledTasks 句柄表（008 ⑦c 已交付）；unregisterProfile 归 30 节
// 已知债：DB 登记 → schedule 两步非原子，schedule 失败时表里有任务、运行时无触发（30 节补 unregister 时一并处置）
```

## 4. 配置键（application.yaml）

```yaml
shell:
  allowed_commands: [ls, cat, python, bash]      # 既有键 + 29 节增补 python/bash
  allowed_interpreters: [python, bash]           # 新键（③ 拍板）：解释器集合，须为 allowed_commands 子集
```

**语义**：解释器命令首 token 双白名单命中才按 L3 校验处理；`allowed_interpreters` 声明了未进 `allowed_commands` 的项构造期 WARN（永不命中、不产生放行面、诊断只含配置键名）。

## 5. 资源加载契约（宪法 IV 渐进式披露）

- **正文**：ContextLoader 每轮从 `agents/<name>/AGENT.md` 现读去 frontmatter，注入 system prompt——**无缓存，改完即时生效**（坑三）
- **Skill 元数据**：注入绑定路径 `<agentDir>/skills/<name>/SKILL.md`（非实体真实路径）；read_file 经 FILE_READ 动态根（= 当前 Agent 目录）透传读取；**未绑定实体路径不可读**（未绑定不可见）
- **L3 脚本**：解释器命令简单形态（`解释器 + scripts/ 下相对路径 + 可选脚本参数`）+ 子进程 cwd = Agent 目录；链式/引号/元字符/解释器级选项/无 Agent 上下文一律拒绝；脚本产出进上下文、代码不进

## 6. 审计契约（沿用，零变化）

`read_file`/`shell` 走既有 `ToolExecutor` 审计路径：`tool_invocations` 成功失败都落（宪法 V）；`read_file` 的 `input_json` 是绑定路径（技术方案 §12.2 验收口径）；一次触发一条日志主线（按会话 id 串起）。

## 7. 30 节前记下的三笔债（本节注记，不交付）

1. `registerProfile` 两步非原子（见 §3）
2. tools 引用告警仅覆盖启动路径——30 节运行时注册须在注册入口重跑同一校验（005 装配校验的运行时版）
3. 启动加载失败 Agent 的清单可见性（当前仅 ERROR 日志——观测盲区，30 节上报）
