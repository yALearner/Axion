---
name: oryxos-admin-ui
description: >-
  生成 OryxOS 管理台前端页面（Vue 3 + Vite，只读五页 + 定时任务页（010 第一个写操作页，⑦d 例外）调
  /api/v1 端点）——固化 OryxOS 官网首页的设计 token（深色 + 橙色强调）、工程约定（base '/admin/'、
  产物落 static/admin、SPA 回落、双信封统一请求封装）、三态规范与验收清单。30 节 Agent 管理页复用同一套。
  当用户说「生成管理台页面 / 给管理台加一页 / 管理台 前端」时使用。
user-invocable: true
disable-model-invocation: false
---

# oryxos-admin-ui：OryxOS 管理台前端规范

OryxOS 管理平台（`/admin`）与官网首页同源的设计语言——本 skill 是**唯一风格来源**，token 值直接取自
`website/.vitepress/theme/custom.css`，生成页面时照抄，不得自创新值。

## 一、设计 token（照抄，一字不改）

- **深色主题**：背景 `#000000`；卡片 `#111111`；悬浮块 `#1a1a1a`；分隔线与边框 `#222222`
- **主色（橙）**：`#f97316` 为主；hover/强调 `#ea6a00` / `#c2550a`——**仅用于强调**（激活项、链接、数值高亮），不铺大面积
- **文字**：主 `#f5f5f5`；次 `#a3a3a3`；弱 `#666666`
- **字体**：正文 Inter；代码/ID/JSON 用 JetBrains Mono（等宽）
- **布局**：左侧竖直导航（深色）+ 右侧内容区；表格深色、行分隔用 `#222`
- **状态**：小圆点/标签——成功绿、失败红、警告橙
- **顶部**：`/logo.svg` + "OryxOS 管理台"；整体克制、留白足、圆角小（4–6px），与官网首页一个气质

## 二、工程约定（不可违反）

1. 技术栈 Vue 3 + Vite（与 website/ 同栈），TypeScript 可选但推荐
2. `vite.config`：`base: '/admin/'`，`build.outDir` 指向 `oryxos-web/src/main/resources/static/admin/`（相对资源路径）
3. 前端源码落 `oryxos-web/src/main/frontend/`；构建 = `npm ci && npm run build`（frontend-maven-plugin 绑进 mvn package；后端迭代用 `-Dskip.npm` 跳过）
4. **只读纪律（⑦d 例外，010-scheduler-mgmt）**：除**定时任务页**外，任何页面不得出现写按钮（新建/编辑/删除）——「能管」要等 30 节，界面上不出现假按钮；定时任务页允许「立即执行」（POST /api/v1/schedules/{id}/run）与「启用·停用」（PUT /api/v1/schedules/{id}）两类写操作——课件 28 节点名的第一个写操作页，其余页面仍只读
5. 除定时任务页外只调 `/api/v1` 的 GET 端点；SPA 路由刷新回落由后端配置（前端只管 `createWebHistory('/admin/')` 或 hash 路由）

## 三、双信封统一请求封装（009 拍板 B，页面不得手写两套解析）

每个前端工程 MUST 内置一个 `src/api.ts`（或等价模块）：

```ts
// 成功信封：{ code: 0, message, data, timestamp }；错误信封：{ errorCode, message, timestamp }
export async function apiGet<T>(path: string): Promise<T> {
  const res = await fetch(path);
  const body = await res.json();
  if (!res.ok || body.errorCode !== undefined) {
    throw new Error(body.message ?? `HTTP ${res.status}`);
  }
  return body.data as T;
}
```

页面一律经 `apiGet<T>` 取数——成功取 `data`、错误取 `message` 展示（三态规范中的「错误态」文案来源）。

## 四、三态规范（每个数据页面必备，别白屏）

- **空数据**：明确占位（如「暂无会话」+ 图标），不白屏不闪烁
- **加载中**：明确 loading 占位（骨架/转圈），请求进行中可感知
- **错误**：展示错误信封的 `message` + 重试按钮（重试调同一 `apiGet`）

## 五、响应式

窄屏（<768px）导航收起（汉堡菜单）；表格小屏可横向滚动；不做移动端专属布局（管理台以桌面为主）。

## 六、验收清单（生成后逐条核对）

- [ ] 六页（会话列表 / Profile 列表 / Tool 列表 / 长期记忆 / 定时任务 / 运行状态）各调对应端点渲染
- [ ] 除定时任务页（立即执行 / 启用·停用）外全站无任何写按钮（⑦d 例外）
- [ ] 双信封解析只出现在 `api.ts` 一处（页面无手写 fetch/JSON 解析）
- [ ] 空/加载/错误三态在每页可见路径上可触达
- [ ] token 值与本文档逐字一致（深色 #000000 系 + 橙 #f97316 仅强调）
- [ ] `npm run build` 产物落 `static/admin/`，`/admin` 下刷新子路由不 404（后端 SPA 回落就位后）
- [ ] 错误态展示的是错误信封 message（统一话术如「服务器内部错误」可读）
