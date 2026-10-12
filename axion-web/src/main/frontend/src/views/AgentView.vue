<script setup>
// Agent 管理页（012-agent-mgmt 5.4 最终形态）：列表 + 新建（只填 name+description → 模板脚手架）+ 删除二次确认。
// 本节点名写操作（只读纪律 ⑦d 例外扩展）：POST /api/v1/agents 创建、DELETE /api/v1/agents/{name} 删除。
import { ref, onMounted } from 'vue'
import { apiGet, apiPost, apiDelete } from '../api'

const loading = ref(true)
const error = ref('')
const agents = ref([])
const creating = ref(false)
const createError = ref('')
const deleting = ref('')
const form = ref({ name: '', description: '' })

async function load() {
  loading.value = true
  error.value = ''
  try {
    agents.value = await apiGet('/api/v1/agents')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function create() {
  creating.value = true
  createError.value = ''
  try {
    await apiPost('/api/v1/agents', form.value)
    form.value = { name: '', description: '' }
    await load()
  } catch (e) {
    createError.value = e.message
  } finally {
    creating.value = false
  }
}

async function removeAgent(name) {
  // 删除二次确认（5.4 口径：删前二次确认；删除 = 注销定时 → 归档目录，可追溯）
  if (!window.confirm(`确认删除 Agent「${name}」？其定时将停止、目录归档（不物理删）。`)) return
  deleting.value = name
  createError.value = ''
  try {
    await apiDelete(`/api/v1/agents/${name}`)
    await load()
  } catch (e) {
    createError.value = e.message
  } finally {
    deleting.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div>
    <h2>Agent 管理</h2>
    <div class="card">
      <h3>新建 Agent</h3>
      <div class="create-form">
        <input v-model="form.name" class="input" placeholder="name（英文小写连字符）" />
        <input v-model="form.description" class="input" placeholder="description（一句话描述它做什么）" />
        <button class="btn" :disabled="creating || !form.name || !form.description" @click="create">
          {{ creating ? '创建中…' : '新建' }}
        </button>
      </div>
      <div class="hint">模板脚手架出完整目录（AGENT.md + scripts/ + skills/ + REFERENCE.md），免重启即上线</div>
      <div v-if="createError" class="action-error">{{ createError }}</div>
    </div>
    <div class="card">
      <div v-if="loading" class="placeholder">加载中…</div>
      <div v-else-if="error" class="placeholder">
        加载失败：{{ error }}
        <button class="retry-btn" @click="load">重试</button>
      </div>
      <div v-else-if="agents.length === 0" class="placeholder">暂无 Agent（新建一个，或直接往 .axion/agents/ 丢目录——几秒内自动上线）</div>
      <table v-else>
        <thead>
          <tr>
            <th>name</th>
            <th>description</th>
            <th>provider / model</th>
            <th>定时</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="agent in agents" :key="agent.name">
            <td class="mono">{{ agent.name }}</td>
            <td>{{ agent.description }}</td>
            <td class="mono">{{ agent.providerName }}{{ agent.model ? ' / ' + agent.model : '' }}</td>
            <td>{{ agent.schedules?.length || 0 }} 条</td>
            <td class="actions">
              <RouterLink class="btn" :to="`/agents/${agent.name}`">详情</RouterLink>
              <button class="btn" :disabled="deleting !== ''" @click="removeAgent(agent.name)">
                {{ deleting === agent.name ? '删除中…' : '删除' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.create-form {
  display: flex;
  gap: 8px;
  margin-bottom: 6px;
}
.input {
  flex: 1;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 4px;
  color: var(--text);
  padding: 7px 10px;
  font-size: 13px;
}
.input::placeholder {
  color: var(--text-weak);
}
a.btn {
  display: inline-block;
  text-decoration: none;
}
h3 {
  color: var(--text);
  font-size: 14px;
  margin-bottom: 10px;
}
</style>
