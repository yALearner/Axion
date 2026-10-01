import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// oryxos-admin-ui skill 工程约定：base '/admin/' + 产物落 static/admin（Spring 托管在 /admin 子路径）
export default defineConfig({
  base: '/admin/',
  plugins: [vue()],
  server: {
    host: '127.0.0.1', // Windows 本机 Node 默认优先 IPv6 ::1——显式绑 IPv4 避免浏览器 localhost 拒绝连接
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    outDir: fileURLToPath(new URL('../resources/static/admin', import.meta.url)),
    emptyOutDir: true,
  },
})
