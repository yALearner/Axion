# 网站开发与部署

本文档涵盖 Axion 文档网站的本地开发和部署流程。

## 技术栈

- **[VitePress](https://vitepress.dev/)** 1.6.4 — 静态站点生成器（Vue 3 + Vite）
- **Node.js** 24+ — 运行时（仅构建网站时需要）
- **npm** — 包管理器

网站是纯静态站点，零运行时依赖——没有后端、没有数据库。构建产物是 HTML + CSS + JS，可以部署到任何静态托管服务。

## 项目结构

```
axion/
├── package.json                    # npm 配置、构建脚本
├── website/
│   ├── index.md                    # 英文首页（渲染 <Home /> 组件）
│   ├── zh/index.md                 # 中文首页
│   ├── .vitepress/
│   │   ├── config.mts              # 站点配置：i18n、导航、侧边栏、SEO
│   │   ├── theme/
│   │   │   ├── index.ts            # 主题入口——注册 Home + Layout
│   │   │   ├── custom.css          # 全局样式 + 品牌 tokens
│   │   │   └── components/
│   │   │       ├── Home.vue        # 首页（7 个区域，800+ 行）
│   │   │       └── Layout.vue      # 布局包装器（扩展点）
│   │   └── dist/                   # 构建输出（git-ignored）
│   ├── public/                     # 静态资源，复制到站点根目录
│   │   ├── favicon.svg
│   │   ├── logo-dark.svg
│   │   ├── logo-light.svg
│   │   └── diagram-architecture.svg
│   ├── docs/                       # 英文文档（7 篇）
│   └── zh/docs/                    # 中文文档（7 篇）
└── .github/workflows/deploy.yml    # GitHub Pages 自动部署
```

## 本地开发

### 环境要求

- Node.js 24+
- npm（随 Node.js 自带）

### 安装与启动

```bash
# 在项目根目录下：
cd axion

# 安装依赖（仅首次）
npm install

# 启动开发服务器（热重载）
npm run docs:dev
```

开发服务器启动在 `http://localhost:5173`。文件修改后页面即时刷新。

### 构建

```bash
npm run docs:build
```

输出到 `website/.vitepress/dist/`。部署的就是这个目录的内容。

### 预览构建结果

```bash
npm run docs:preview
```

在本地预览构建后的站点，用于部署前最终检查。

## 内容编写

### 新增文档页面

1. 创建英文版：`website/docs/my-page.md`
2. 创建中文版：`website/zh/docs/my-page.md`
3. 在 `website/.vitepress/config.mts` 中，分别在两个 locale 的 sidebar 里添加页面链接

### 首页多语言

首页使用 `t(zh, en)` 辅助函数：

```vue
{{ t('中文文本', 'English text') }}
```

### Frontmatter

VitePress 页面可以包含 YAML frontmatter 元数据：

```yaml
---
title: 页面标题
description: SEO 描述
---
```

### 链接写法

- 内部链接：使用相对路径，不带 `.md` 扩展名，如 `[系统架构](./architecture)`
- `cleanUrls: true` 配置使生产环境下 URL 中不含 `.html`

## 部署

### 生产地址

`https://axion.dev`（确定正式域名后需更新 `config.mts` 和 index.md 文件中的域名）。

### 方式一：GitHub Pages（推荐）

仓库已包含 `.github/workflows/deploy.yml`，实现了自动部署：

```yaml
触发条件：推送至 main 分支
步骤：
  1. Checkout 仓库
  2. 安装 Node.js 24
  3. npm install
  4. npm run docs:build
  5. 上传 website/.vitepress/dist 作为 Pages artifact
  6. 部署到 GitHub Pages
```

**首次配置：**

1. 将仓库推送到 GitHub
2. 进入 **Settings → Pages**
3. 在 **Build and deployment** 中，选择 **Source: GitHub Actions**
4. 推送至 `main` ——流水线自动部署

**自定义域名：**

1. 在 **Settings → Pages → Custom domain** 中添加域名
2. 配置 DNS：添加 CNAME 记录指向 `<org>.github.io`
3. 其余由流水线自动处理

### 方式二：手动部署

构建后部署到任意静态托管服务：

```bash
npm run docs:build

# 将 website/.vitepress/dist/ 部署到：
# - Nginx/Apache：复制 dist/* 到 Web 根目录
# - Cloudflare Pages：关联仓库，构建命令 npm run docs:build，输出目录 website/.vitepress/dist
# - Vercel：导入仓库，框架选 VitePress，输出目录 website/.vitepress/dist
# - Netlify：导入仓库，构建命令 npm run docs:build，发布目录 website/.vitepress/dist
# - 任意 S3 存储桶 + CDN
```

### 方式三：集成到 axion-web

Axion 的 `axion-web` 模块启动时会暴露一个 REST API 服务。可以将构建好的静态文件放入 `axion-web/src/main/resources/static/`，让 Spring Boot 直接服务网站：

```bash
# 1. 构建网站
npm run docs:build

# 2. 复制到 Spring Boot 静态资源目录
cp -r website/.vitepress/dist/* axion-web/src/main/resources/static/

# 3. 重新打包
mvn clean package -DskipTests

# 4. 启动——网站和 API 在同一个端口
java -jar axion-boot/target/axion-boot-*.jar serve --port 8080
# 网站: http://localhost:8080/
# API:  http://localhost:8080/api/v1/health
```

> **注意**：这种方式下 VitePress 的 `cleanUrls` 功能需要 Spring Boot 配合 URL 重写。推荐在扩展阶段再做，核心阶段用独立的静态托管。

## 维护指南

### 更新品牌资源

- `website/public/logo-light.svg` — 导航栏 Logo（深色文字，浅色背景用）
- `website/public/logo-dark.svg` — 深色模式 Logo（浅色文字，深色背景用）
- `website/public/favicon.svg` — 浏览器标签页图标（32×32）
- `website/public/diagram-architecture.svg` — 首页架构图

### CSS 定制

品牌 tokens 在 `website/.vitepress/theme/custom.css` 的 `:root` 中定义：

```css
--axion-teal: #2dd4bf;
--axion-indigo: #818cf8;
--axion-cyan: #22d3ee;
```

首页样式**隔离**在 `Home.vue` 的 `<style scoped>` 中——不会影响其他页面。

### 升级 VitePress

```bash
npm install vitepress@latest
```

升级前请查阅 [VitePress 更新日志](https://github.com/vuejs/vitepress/blob/main/CHANGELOG.md) 了解破坏性变更。
