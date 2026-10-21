import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig({
  plugins: [vue()],
  server: { proxy: { '/onto': 'http://127.0.0.1:18182' } },
  build: { chunkSizeWarningLimit: 1200 },
})
