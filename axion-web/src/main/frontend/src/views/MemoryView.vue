<script setup>
import { ref, onMounted } from 'vue'
import { apiGet } from '../api'

const loading = ref(true)
const error = ref('')
const content = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await apiGet('/api/v1/memory')
    content.value = data.content
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
    <h2>长期记忆</h2>
    <div class="card">
      <div v-if="loading" class="placeholder">加载中…</div>
      <div v-else-if="error" class="placeholder">
        加载失败：{{ error }}
        <button class="retry-btn" @click="load">重试</button>
      </div>
      <div v-else-if="!content" class="placeholder">暂无长期记忆（MEMORY.md 为空）</div>
      <pre v-else class="memory">{{ content }}</pre>
    </div>
  </div>
</template>

<style scoped>
.memory {
  white-space: pre-wrap;
  font-family: 'JetBrains Mono', monospace;
  color: var(--text);
  font-size: 13px;
  line-height: 1.6;
}
</style>
