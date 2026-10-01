<script setup>
// 定时任务页（010-scheduler-mgmt）——管理台第一个写操作页（⑦d 例外条款）：立即执行（POST run，同步等待 60s 上限）
// + 启用·停用（PUT enabled）；其余页面仍只读。时间字段后端返 UTC，展示按任务 zone 转本地（Clarifications 2026-09-09）。
import { ref, onMounted } from 'vue'
import { apiGet, apiPost, apiPut } from '../api'

const loading = ref(true)
const error = ref('')
const tasks = ref([])
const busyTaskId = ref('') // 正在立即执行的任务（按钮 loading 态）
const actionError = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    tasks.value = await apiGet('/api/v1/schedules')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function formatLocal(iso, zone) {
  if (!iso) return '—'
  try {
    return new Intl.DateTimeFormat('zh-CN', {
      timeZone: zone || undefined,
      dateStyle: 'short',
      timeStyle: 'medium',
    }).format(new Date(iso))
  } catch {
    return iso
  }
}

function statusLabel(task) {
  if (!task.enabled) return { text: '已停用', cls: 'status-off' }
  // 文案口径（2026-09-10 拍板）：success = 循环完整跑完并给出答复，不代表所有工具都成功——
  // 工具级成败在 task_executions 之外的 tool_invocations 审计里，故显示「上次完成」而非「上次成功」
  if (task.lastStatus === 'success') return { text: '上次完成', cls: 'status-ok' }
  if (task.lastStatus === 'failure') return { text: '上次失败', cls: 'status-bad' }
  return { text: '未执行过', cls: 'status-idle' }
}

async function runNow(taskId) {
  busyTaskId.value = taskId
  actionError.value = ''
  try {
    const execution = await apiPost(`/api/v1/schedules/${taskId}/run`)
    // 同步等待返回本次执行记录（与历史条目同形状）——成功/失败都展示结果
    actionError.value = execution.success
      ? ''
      : `执行失败：${execution.errorMessage ?? '未知错误'}`
    await load()
  } catch (e) {
    actionError.value = e.message
  } finally {
    busyTaskId.value = ''
  }
}

async function toggleEnabled(task) {
  actionError.value = ''
  try {
    await apiPut(`/api/v1/schedules/${task.taskId}`, { enabled: !task.enabled })
    await load()
  } catch (e) {
    actionError.value = e.message
  }
}

onMounted(load)
</script>

<template>
  <div>
    <h2>定时任务</h2>
    <div class="card">
      <div v-if="loading" class="placeholder">加载中…</div>
      <div v-else-if="error" class="placeholder">
        加载失败：{{ error }}
        <button class="retry-btn" @click="load">重试</button>
      </div>
      <div v-else-if="tasks.length === 0" class="placeholder">暂无定时任务（AGENT.md frontmatter 未配置 schedules）</div>
      <template v-else>
        <div v-if="actionError" class="action-error">{{ actionError }}</div>
        <table class="schedule-table">
          <thead>
            <tr>
              <th>任务</th>
              <th>Profile</th>
              <th>cron</th>
              <th>下次触发</th>
              <th>上次结果</th>
              <th>次数</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="task in tasks" :key="task.taskId">
              <td class="mono">{{ task.taskId }}</td>
              <td>{{ task.profileName }}</td>
              <td class="mono">{{ task.cron }}</td>
              <td>{{ formatLocal(task.nextRunAt, task.zone) }}</td>
              <td>{{ formatLocal(task.lastRunAt, task.zone) }}</td>
              <td>{{ task.runCount }}</td>
              <td><span class="status" :class="statusLabel(task).cls">{{ statusLabel(task).text }}</span></td>
              <td class="actions">
                <button class="btn" :disabled="busyTaskId !== ''" @click="runNow(task.taskId)">
                  {{ busyTaskId === task.taskId ? '执行中…' : '立即执行' }}
                </button>
                <button class="btn" :disabled="busyTaskId !== ''" @click="toggleEnabled(task)">
                  {{ task.enabled ? '停用' : '启用' }}
                </button>
                <!-- 停用状态提示（2026-09-10 拍板）：停用只拦到点自动触发，立即执行（手动补跑）不受影响 -->
                <div v-if="!task.enabled" class="hint">停用仅停止到点自动触发，立即执行不受影响</div>
              </td>
            </tr>
          </tbody>
        </table>
      </template>
    </div>
  </div>
</template>

<style scoped>
.schedule-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.schedule-table th {
  text-align: left;
  color: #a3a3a3;
  font-weight: 500;
  padding: 10px 12px;
  border-bottom: 1px solid #222222;
}
.schedule-table td {
  padding: 10px 12px;
  border-bottom: 1px solid #222222;
  color: var(--text);
}
.mono {
  font-family: 'JetBrains Mono', monospace;
}
.status {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
}
.status-ok {
  color: #4ade80;
  border: 1px solid #166534;
}
.status-bad {
  color: #f87171;
  border: 1px solid #7f1d1d;
}
.status-off {
  color: #a3a3a3;
  border: 1px solid #404040;
}
.status-idle {
  color: #666666;
  border: 1px solid #333333;
}
.actions {
  white-space: nowrap;
}
.hint {
  color: #666666;
  font-size: 12px;
  margin-top: 4px;
}
.btn {
  background: #1a1a1a;
  color: #f5f5f5;
  border: 1px solid #222222;
  border-radius: 4px;
  padding: 6px 12px;
  margin-right: 8px;
  cursor: pointer;
  font-size: 13px;
}
.btn:hover:not(:disabled) {
  border-color: #f97316;
  color: #f97316;
}
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.action-error {
  color: #f87171;
  margin-bottom: 12px;
  font-size: 13px;
}
</style>
