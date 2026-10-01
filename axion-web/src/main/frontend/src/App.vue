<script setup>
// oryxos-admin-ui skill：左侧竖直导航（深色）+ 右侧内容区；只读五页 + 定时任务页（010：第一个写操作页，⑦d 例外条款）
const navItems = [
  { path: '/sessions', label: '会话列表' },
  { path: '/profiles', label: 'Profile 列表' },
  { path: '/tools', label: 'Tool 列表' },
  { path: '/memory', label: '长期记忆' },
  { path: '/schedules', label: '定时任务' },
  { path: '/status', label: '运行状态' },
]
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">
        <span>OryxOS 管理台</span>
      </div>
      <nav>
        <RouterLink v-for="item in navItems" :key="item.path" :to="item.path" class="nav-item">
          {{ item.label }}
        </RouterLink>
      </nav>
    </aside>
    <main class="content">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  min-height: 100vh;
}
.sidebar {
  width: 280px;
  background: #111111;
  border-right: 1px solid #222222;
  padding: 20px 12px;
  flex-shrink: 0;
}
.brand {
  color: #f5f5f5;
  font-weight: 600;
  font-size: 16px;
  margin-bottom: 24px;
  padding: 0 12px;
}
.nav-item {
  display: block;
  padding: 10px 12px;
  color: #a3a3a3;
  text-decoration: none;
  border-radius: 4px;
  margin-bottom: 4px;
}
.nav-item.router-link-active {
  color: #f97316;
  background: #1a1a1a;
}
.content {
  flex: 1;
  padding: 24px;
}
</style>
