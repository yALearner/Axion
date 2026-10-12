<script setup>
// Agent 详情页（012-agent-mgmt 5.4 最终形态）——5 个 tab：
// 基本信息（PUT 覆写 AGENT.md）/ 生成（generate-files 草稿预览可改 → files 保存并生效）/
// 文件（tree 左树 + 右编辑保存，AGENT.md 走 update 重注册）/ 会话（admin:console 固定会话气泡 + 输入框）/
// 记忆（per-agent MEMORY.md 只读）。三态规范 + 深蓝 token 沿用 axion-admin-ui skill。
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { apiGet, apiPost, apiPut } from '../api'
import FileNode from './FileNode.vue'

const route = useRoute()
const name = route.params.name
const tab = ref('basic')

const loading = ref(true)
const error = ref('')
const agent = ref(null)

// 基本信息
const basicSaving = ref(false)
const actionError = ref('')
const agentMd = ref('')

// 生成
const genDescription = ref('')
const generating = ref(false)
const draftFiles = ref(null)

// 文件
const tree = ref(null)
const treeError = ref('')
const currentPath = ref('')
const fileContent = ref('')
const fileSaving = ref(false)

// 会话
const session = ref(null)
const chatInput = ref('')
const sending = ref(false)

// 记忆
const memory = ref('')

async function loadBasic() {
  loading.value = true
  error.value = ''
  try {
    agent.value = await apiGet(`/api/v1/agents/${name}`)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function saveBasic() {
  basicSaving.value = true
  actionError.value = ''
  try {
    agent.value = await apiPut(`/api/v1/agents/${name}`, { agentMd: agentMd.value })
  } catch (e) {
    actionError.value = e.message
  } finally {
    basicSaving.value = false
  }
}

async function generate() {
  generating.value = true
  actionError.value = ''
  try {
    draftFiles.value = await apiPost(`/api/v1/agents/${name}/generate-files`, {
      description: genDescription.value,
    })
  } catch (e) {
    actionError.value = e.message
  } finally {
    generating.value = false
  }
}

async function saveDraft() {
  generating.value = true
  actionError.value = ''
  try {
    await apiPost(`/api/v1/agents/${name}/files`, { files: draftFiles.value })
    draftFiles.value = null
    await loadBasic()
  } catch (e) {
    actionError.value = e.message
  } finally {
    generating.value = false
  }
}

async function loadTree() {
  treeError.value = ''
  try {
    tree.value = await apiGet('/api/v1/workspace/tree')
  } catch (e) {
    treeError.value = e.message
  }
}

async function openFile(path) {
  currentPath.value = path
  try {
    const res = await apiGet(`/api/v1/workspace/file?path=${encodeURIComponent(path)}`)
    fileContent.value = res.content
  } catch (e) {
    treeError.value = e.message
  }
}

async function saveFile() {
  fileSaving.value = true
  treeError.value = ''
  try {
    await apiPost('/api/v1/workspace/file', { path: currentPath.value, content: fileContent.value })
    if (currentPath.value.endsWith('AGENT.md')) await loadBasic() // 重注册已生效
  } catch (e) {
    treeError.value = e.message
  } finally {
    fileSaving.value = false
  }
}

async function loadSession() {
  try {
    session.value = await apiGet(`/api/v1/agents/${name}/session`)
  } catch (e) {
    treeError.value = e.message
  }
}

async function sendMessage() {
  if (!chatInput.value.trim()) return
  sending.value = true
  treeError.value = ''
  try {
    await apiPost(`/api/v1/agents/${name}/session/messages`, { content: chatInput.value })
    chatInput.value = ''
    await loadSession()
  } catch (e) {
    treeError.value = e.message
  } finally {
    sending.value = false
  }
}

async function loadMemory() {
  try {
    const res = await apiGet(`/api/v1/agents/${name}/memory`)
    memory.value = res.content
  } catch (e) {
    treeError.value = e.message
  }
}

async function switchTab(t) {
  tab.value = t
  if (t === 'basic') await loadBasic()
  else if (t === 'generate') { genDescription.value = agent.value?.description ?? '' }
  else if (t === 'files') await loadTree()
  else if (t === 'session') await loadSession()
  else if (t === 'memory') await loadMemory()
}

onMounted(() => switchTab('basic'))
</script>

<template>
  <div>
    <h2>Agent 详情：<span class="mono">{{ name }}</span></h2>
    <div class="tabs">
      <button v-for="t in ['basic', 'generate', 'files', 'session', 'memory']" :key="t"
              class="tab" :class="{ active: tab === t }" @click="switchTab(t)">
        {{ { basic: '基本信息', generate: '生成', files: '文件', session: '会话', memory: '记忆' }[t] }}
      </button>
    </div>

    <div v-if="loading && tab === 'basic'" class="placeholder">加载中…</div>
    <div v-else-if="error && tab === 'basic'" class="placeholder">
      加载失败：{{ error }}
      <button class="retry-btn" @click="loadBasic">重试</button>
    </div>

    <div class="card" v-show="tab === 'basic'">
      <template v-if="agent">
        <div class="info-line">name：<span class="mono">{{ agent.name }}</span></div>
        <div class="info-line">description：{{ agent.description }}</div>
        <div class="info-line">provider / model：<span class="mono">{{ agent.providerName }} / {{ agent.model }}</span></div>
        <div class="info-line">tools：<span class="mono">{{ (agent.tools || []).join(', ') || '（空）' }}</span></div>
        <div class="info-line">定时：{{ (agent.schedules || []).map(s => `${s.id} ${s.cron}`).join('；') || '（无）' }}</div>
        <h3>AGENT.md（整份覆写，保存即生效；schedules 变更先注销旧定时）</h3>
        <textarea v-model="agentMd" class="editor" rows="14"></textarea>
        <div class="hint">改 body/provider 即时生效（无需重启）；改 schedules 自动先注销旧句柄再注册新</div>
        <div v-if="actionError" class="action-error">{{ actionError }}</div>
        <button class="btn" :disabled="basicSaving || !agentMd" @click="saveBasic">
          {{ basicSaving ? '保存中…' : '保存并生效' }}
        </button>
      </template>
    </div>

    <div class="card" v-show="tab === 'generate'">
      <div class="info-line">填一句描述，用大模型生成 AGENT.md 草稿——每个文件可编辑预览，确认后「保存并生效」（不落盘直到你确认）</div>
      <div class="create-form">
        <input v-model="genDescription" class="input" placeholder="如：每天早上九点查北京天气，把穿搭建议发到团队群" />
        <button class="btn" :disabled="generating || !genDescription" @click="generate">
          {{ generating ? '生成中…' : '用大模型生成' }}
        </button>
      </div>
      <template v-if="draftFiles">
        <div v-for="(content, path) in draftFiles" :key="path" class="draft-item">
          <div class="hint mono">{{ path }}</div>
          <textarea v-model="draftFiles[path]" class="editor" rows="12"></textarea>
        </div>
        <div v-if="actionError" class="action-error">{{ actionError }}</div>
        <button class="btn" :disabled="generating" @click="saveDraft">保存并生效</button>
        <button class="btn" @click="draftFiles = null">放弃</button>
      </template>
      <div v-else-if="actionError" class="action-error">{{ actionError }}</div>
    </div>

    <div class="card" v-show="tab === 'files'">
      <div class="file-layout">
        <div class="file-tree">
          <div v-if="treeError" class="action-error">{{ treeError }}</div>
          <FileNode v-if="tree" :node="tree" @open="openFile" />
        </div>
        <div class="file-view">
          <template v-if="currentPath">
            <div class="hint mono">{{ currentPath }}</div>
            <textarea v-model="fileContent" class="editor" rows="18"></textarea>
            <button class="btn" :disabled="fileSaving" @click="saveFile">
              {{ fileSaving ? '保存中…' : '保存' }}
            </button>
          </template>
          <div v-else class="placeholder">左侧选择文件查看/编辑</div>
        </div>
      </div>
    </div>

    <div class="card" v-show="tab === 'session'">
      <div class="hint">固定会话 <span class="mono">{{ session?.sessionId ?? '…' }}</span>（channel=admin、user=console——上下文可累积）</div>
      <div v-if="session" class="chat">
        <div v-for="(m, i) in session.messages" :key="i" class="bubble" :class="m.role?.toLowerCase()">
          <span class="role">{{ m.role }}</span> {{ m.content }}
        </div>
        <div v-if="!session.messages?.length" class="placeholder">暂无消息——发一条试试</div>
      </div>
      <div v-if="treeError" class="action-error">{{ treeError }}</div>
      <div class="create-form">
        <input v-model="chatInput" class="input" placeholder="给这个 Agent 发消息…" @keyup.enter="sendMessage" />
        <button class="btn" :disabled="sending || !chatInput.trim()" @click="sendMessage">
          {{ sending ? '发送中…' : '发送' }}
        </button>
      </div>
    </div>

    <div class="card" v-show="tab === 'memory'">
      <div class="hint">本 Agent 的专属记忆（由 save_memory 写入，只读）</div>
      <pre v-if="memory" class="memory">{{ memory }}</pre>
      <div v-else class="placeholder">暂无记忆</div>
    </div>
  </div>
</template>

<style scoped>
.tabs { display: flex; gap: 6px; margin-bottom: 14px; }
.tab {
  background: transparent; border: 1px solid var(--border); color: var(--text-secondary);
  border-radius: 4px; padding: 6px 14px; cursor: pointer; font-size: 13px;
}
.tab.active { color: var(--primary); border-color: var(--primary); background: var(--hover); }
.info-line { margin-bottom: 8px; color: var(--text); font-size: 13px; }
.editor {
  width: 100%; background: var(--bg); border: 1px solid var(--border); border-radius: 4px;
  color: var(--text); padding: 10px; font-family: 'JetBrains Mono', monospace; font-size: 13px; margin: 8px 0;
}
.create-form { display: flex; gap: 8px; margin: 8px 0; }
.input {
  flex: 1; background: var(--bg); border: 1px solid var(--border); border-radius: 4px;
  color: var(--text); padding: 7px 10px; font-size: 13px;
}
.input::placeholder { color: var(--text-weak); }
.file-layout { display: flex; gap: 16px; }
.file-tree { width: 300px; flex-shrink: 0; }
.file-view { flex: 1; }
.chat { display: flex; flex-direction: column; gap: 8px; margin: 10px 0; max-height: 380px; overflow-y: auto; }
.bubble { padding: 8px 12px; border-radius: 6px; font-size: 13px; background: var(--hover); color: var(--text); }
.bubble .role { color: var(--primary); margin-right: 6px; font-size: 12px; }
.memory { white-space: pre-wrap; color: var(--text); font-size: 13px; background: var(--bg); padding: 12px; border: 1px solid var(--border); border-radius: 4px; }
h3 { color: var(--text); font-size: 14px; margin: 12px 0 6px; }
</style>
