<script setup lang="ts">
/** 文件输入只形成 multipart；8 MiB 和扩展名仅提前反馈，内容判定仍由服务端执行。 */
import { ref } from 'vue'
import type { ManagedFile } from '@onto/api-client'
defineProps<{ canWrite: boolean; pending: boolean; stale: boolean; files: ManagedFile[] }>()
const emit = defineEmits<{ upload: [file: File] }>(),
  error = ref('')
function choose(event: Event) {
  error.value = ''
  const input = event.target as HTMLInputElement,
    file = input.files?.[0]
  if (!file) return
  if (file.size > 8 * 1024 * 1024) error.value = '文件不能超过 8 MiB。'
  else if (!/\.(csv|json|xlsx|pdf|docx|txt)$/i.test(file.name))
    error.value = '仅允许 CSV / JSON / XLSX / PDF / DOCX / TXT。'
  else emit('upload', file)
  input.value = ''
}
</script>
<template>
  <section class="panel stack">
    <h2>受管文件上传</h2>
    <p class="muted">
      OWNER 可上传最大 8 MiB 文件。结构文件用于映射与抽取，文档用于建模抽取；服务端校验真实内容。
    </p>
    <label>
      选择文件
      <input
        type="file"
        accept=".csv,.json,.xlsx,.pdf,.docx,.txt"
        :disabled="!canWrite || pending || stale"
        @change="choose"
      />
    </label>
    <p v-if="error" class="error-text" role="alert">{{ error }}</p>
    <dl v-for="file in files" :key="file.id" class="details-grid">
      <dt>{{ file.name }}</dt>
      <dd>
        {{ file.size }} bytes ·
        <code>{{ file.id }}</code>
        <br />
        访问范围：
        <code>{{ file.resource }}</code>
        <br />
        SHA-256：
        <code>{{ file.sha256 }}</code>
      </dd>
    </dl>
  </section>
</template>
