import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import SessionsView from './views/SessionsView.vue'
import ProfilesView from './views/ProfilesView.vue'
import ToolsView from './views/ToolsView.vue'
import MemoryView from './views/MemoryView.vue'
import StatusView from './views/StatusView.vue'
import SchedulesView from './views/SchedulesView.vue'
import './style.css'

// base '/admin/'：history 路由 + 后端 SPA 回落（/admin/** 未命中 → index.html，009 T020 配置）
const router = createRouter({
  history: createWebHistory('/admin/'),
  routes: [
    { path: '/', redirect: '/sessions' },
    { path: '/sessions', component: SessionsView },
    { path: '/profiles', component: ProfilesView },
    { path: '/tools', component: ToolsView },
    { path: '/memory', component: MemoryView },
    { path: '/schedules', component: SchedulesView },
    { path: '/status', component: StatusView },
  ],
})

createApp(App).use(router).mount('#app')
