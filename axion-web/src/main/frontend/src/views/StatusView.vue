<script setup>
import { ref, onMounted } from 'vue'
import { apiGet } from '../api'

const loading = ref(true)
const error = ref('')
const health = ref(null)
const providers = ref([])

async function load() {
  loading.value = true
  error.value = ''
  try {
    health.value = await apiGet('/api/v1/health')
    const info = await apiGet('/api/v1/info')
    providers.value = info.providers
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
    <h2>运行状态</h2>
    <div class="card">
      <div v-if="loading" class="placeholder">加载中…</div>
      <div v-else-if="error" class="placeholder">
        加载失败：{{ error }}
        <button class="retry-btn" @click="load">重试</button>
      </div>
      <div v-else>
        <p><span class="status-dot ok"></span> 服务状态：{{ health?.status ?? 'ok' }}</p>
        <table>
          <thead>
            <tr>
              <th>Provider</th>
              <th>baseUrl</th>
              <th>model</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in providers" :key="p.name">
              <td><code>{{ p.name }}</code></td>
              <td>{{ p.baseUrl ?? '—' }}</td>
              <td>{{ p.model ?? '—' }}</td>
              <td><span class="status-dot ok"></span>{{ p.status }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="providers.length === 0" class="placeholder">暂无 Provider 声明</div>
      </div>
    </div>
  </div>
</template>
