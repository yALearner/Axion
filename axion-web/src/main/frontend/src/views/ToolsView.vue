<script setup>
import { ref, onMounted } from 'vue'
import { apiGet } from '../api'

const loading = ref(true)
const error = ref('')
const tools = ref([])

async function load() {
  loading.value = true
  error.value = ''
  try {
    tools.value = await apiGet('/api/v1/tools')
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
    <h2>Tool 列表</h2>
    <div class="card">
      <div v-if="loading" class="placeholder">加载中…</div>
      <div v-else-if="error" class="placeholder">
        加载失败：{{ error }}
        <button class="retry-btn" @click="load">重试</button>
      </div>
      <div v-else-if="tools.length === 0" class="placeholder">暂无 Tool</div>
      <table v-else>
        <thead>
          <tr>
            <th>name</th>
            <th>description</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in tools" :key="t.name">
            <td><code>{{ t.name }}</code></td>
            <td>{{ t.description }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
