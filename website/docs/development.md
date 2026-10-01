# Website Development & Deployment

This document covers how to develop the Axion documentation website locally and deploy it to production.

## Tech Stack

- **[VitePress](https://vitepress.dev/)** 1.6.4 — static site generator (Vue 3 + Vite)
- **Node.js** 24+ — runtime (only needed for building the website)
- **npm** — package manager

The website is a pure static site. It has zero runtime dependencies — no backend, no database. The built output is HTML + CSS + JS that can be served from any static host.

## Project Structure

```
axion/
├── package.json                    # npm config, build scripts
├── website/
│   ├── index.md                    # English home page (renders <Home />)
│   ├── zh/index.md                 # Chinese home page
│   ├── .vitepress/
│   │   ├── config.mts              # Site config: i18n, nav, sidebar, SEO
│   │   ├── theme/
│   │   │   ├── index.ts            # Theme entry — registers Home + Layout
│   │   │   ├── custom.css          # Global styles + brand tokens
│   │   │   └── components/
│   │   │       ├── Home.vue        # Home page (7 sections, 800+ lines)
│   │   │       └── Layout.vue      # Layout wrapper (extension point)
│   │   └── dist/                   # Build output (git-ignored)
│   ├── public/                     # Static assets copied to root
│   │   ├── favicon.svg
│   │   ├── logo-dark.svg
│   │   ├── logo-light.svg
│   │   └── diagram-architecture.svg
│   ├── docs/                       # English docs (7 pages)
│   └── zh/docs/                    # Chinese docs (7 pages)
└── .github/workflows/deploy.yml    # GitHub Pages auto-deploy
```

## Local Development

### Prerequisites

- Node.js 24+
- npm (ships with Node.js)

### Setup

```bash
# From the project root:
cd axion

# Install dependencies (first time only)
npm install

# Start dev server with hot reload
npm run docs:dev
```

The dev server starts at `http://localhost:5173`. Pages reload instantly on file changes.

### Build

```bash
npm run docs:build
```

Output goes to `website/.vitepress/dist/`. This is what gets deployed.

### Preview the built site

```bash
npm run docs:preview
```

This serves the `dist/` directory locally for a final check before deploying.

## Writing Content

### Adding a new doc page

1. Create the English version: `website/docs/my-page.md`
2. Create the Chinese version: `website/zh/docs/my-page.md`
3. Add to the sidebar in `website/.vitepress/config.mts` under both `locales.root.themeConfig.sidebar` and `locales.zh.themeConfig.sidebar`

### i18n in Home.vue

The Home page uses a `t(zh, en)` helper:

```vue
{{ t('中文文本', 'English text') }}
```

### Frontmatter

VitePress pages can include YAML frontmatter for metadata:

```yaml
---
title: Page Title
description: SEO description
---
```

### Links

- Internal links: use relative paths without `.md` extension, e.g. `[Architecture](./architecture)`
- The `cleanUrls: true` config drops `.html` from URLs in production

## Deployment

### Production URL

`https://axion.dev` (update `config.mts` and index.md files when the real domain is known).

### Method: GitHub Pages (recommended)

The repo includes `.github/workflows/deploy.yml` which automates deployment:

```yaml
Trigger: push to main branch
Steps:
  1. Checkout repo
  2. Setup Node.js 24
  3. npm install
  4. npm run docs:build
  5. Upload website/.vitepress/dist as Pages artifact
  6. Deploy to GitHub Pages
```

**First-time setup:**

1. Push the repo to GitHub
2. Go to **Settings → Pages**
3. Under **Build and deployment**, select **Source: GitHub Actions**
4. Push to `main` — the workflow deploys automatically

**Custom domain:**

1. Add your domain in **Settings → Pages → Custom domain**
2. Configure DNS: add a CNAME record pointing to `<org>.github.io`
3. The workflow handles the rest

### Alternative: Manual deploy

Build and serve from any static host:

```bash
npm run docs:build

# Deploy website/.vitepress/dist/ to:
# - Nginx/Apache: copy dist/* to web root
# - Cloudflare Pages: point to repo, build command: npm run docs:build, output dir: website/.vitepress/dist
# - Vercel: import repo, framework: VitePress, output dir: website/.vitepress/dist
# - Netlify: import repo, build: npm run docs:build, publish: website/.vitepress/dist
# - Any S3 bucket + CDN
```

## Maintenance Notes

### Updating brand assets

- `website/public/logo-light.svg` — shown in the nav bar (dark text, for light backgrounds)
- `website/public/logo-dark.svg` — dark-mode variant (white text, for dark backgrounds)
- `website/public/favicon.svg` — browser tab icon (32×32)
- `website/public/diagram-architecture.svg` — architecture diagram on the home page

### CSS customization

Brand tokens are in `website/.vitepress/theme/custom.css` under `:root`:

```css
--axion-teal: #2dd4bf;
--axion-indigo: #818cf8;
--axion-cyan: #22d3ee;
```

Home page styles are **scoped** inside `Home.vue` — they don't leak to other pages.

### Upgrading VitePress

```bash
npm install vitepress@latest
```

Check the [VitePress changelog](https://github.com/vuejs/vitepress/blob/main/CHANGELOG.md) for breaking changes before upgrading.
