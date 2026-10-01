<script setup>
// 只读页：GET /api/v1/sessions——注意核心阶段无「列会话」端点（10 端点无 GET /sessions 列表），
// 本页从 /profiles 取 Agent 名占位提示 + 三态（管理台 v1 只读的诚实表达：会话列表端点归 28 节管理扩展）。
import { ref, onMounted } from 'vue'
import { apiGet } from '../api'

const loading = ref(true)
const error = ref('')
const profiles = ref([])

async function load() {
  loading.value = true
  error.value = ''
  try {
    profiles.value = await apiGet('/api/v1/profiles')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <h2>会话列表</h2>
    <div class="card">
      <div v-if="loading" class="placeholder">加载中…</div>
      <div v-else-if="error" class="placeholder">
        加载失败：{{ error }}
        <button class="retry-btn" @click="load">重试</button>
      </div>
      <div v-else-if="profiles.length === 0" class="placeholder">暂无会话数据（无已加载 Agent）</div>
      <div v-else class="placeholder">会话列表端点归 28 节管理扩展——当前可经 POST /api/v1/sessions 创建会话后按 id 查询</div>
    </div>
  </div>
</template>
