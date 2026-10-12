import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import SessionsView from './views/SessionsView.vue'
import ProfilesView from './views/ProfilesView.vue'
import ToolsView from './views/ToolsView.vue'
import StatusView from './views/StatusView.vue'
import SchedulesView from './views/SchedulesView.vue'
import AgentView from './views/AgentView.vue'
import AgentDetailView from './views/AgentDetailView.vue'
import './style.css'

// base '/admin/'：history 路由 + 后端 SPA 回落（/admin/** 未命中 → index.html，009 T020 配置）
// 012：长期记忆页退役（全局端点移除）→ Agent 管理页 + 详情 5 tab 取代
const router = createRouter({
  history: createWebHistory('/admin/'),
  routes: [
    { path: '/', redirect: '/sessions' },
    { path: '/sessions', component: SessionsView },
    { path: '/profiles', component: ProfilesView },
    { path: '/tools', component: ToolsView },
    { path: '/agents', component: AgentView },
    { path: '/agents/:name', component: AgentDetailView },
    { path: '/schedules', component: SchedulesView },
    { path: '/status', component: StatusView },
  ],
})

createApp(App).use(router).mount('#app')
