<script setup>
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
    <h2>Profile 列表</h2>
    <div class="card">
      <div v-if="loading" class="placeholder">加载中…</div>
      <div v-else-if="error" class="placeholder">
        加载失败：{{ error }}
        <button class="retry-btn" @click="load">重试</button>
      </div>
      <div v-else-if="profiles.length === 0" class="placeholder">暂无 Profile</div>
      <table v-else>
        <thead>
          <tr>
            <th>name</th>
            <th>agent_name</th>
            <th>description</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in profiles" :key="p.name">
            <td><code>{{ p.name }}</code></td>
            <td>{{ p.agentName ?? '—' }}</td>
            <td>{{ p.description ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
