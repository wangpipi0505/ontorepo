<script setup lang="ts">
/** 预览和应用是两个明确动作；内容改变即撤销确认，不解析外部 imports，也不在本地模拟导入成功。 */
import { computed, reactive, ref, watch } from 'vue'
import type { ImportPreview, Space } from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import { useDraftRevision } from '../../composables/useDraftRevision'
import { useMutation } from '../../composables/useMutation'
const api = useSession().api,
  workspace = useWorkspace(),
  mutation = useMutation(),
  exporting = useMutation(),
  draft = useDraftRevision()
const form = reactive({ kind: 'TTL' as 'TTL' | 'OWL', content: '' }),
  preview = ref<ImportPreview | null>(null),
  confirmed = ref(false),
  exported = ref('')
const canApply = computed(
  () =>
    workspace.canWrite &&
    !draft.stale.value &&
    preview.value?.status === 'PREVIEW' &&
    !preview.value.errors.length &&
    !preview.value.conflicts.length &&
    confirmed.value &&
    preview.value.targetDraftRev === workspace.space?.draftRev,
)
watch(
  () => [form.kind, form.content],
  () => {
    preview.value = null
    confirmed.value = false
  },
)
async function makePreview() {
  const space = workspace.space
  if (!space || !workspace.canWrite) return
  if (!form.content.trim()) {
    mutation.error.value = '请粘贴或选择 TTL / OWL 内容。'
    return
  }
  preview.value = null
  confirmed.value = false
  draft.start(space.draftRev)
  await mutation.run(({ isCurrent }) =>
    draft.submit(async (expectedDraftRev) => {
      const result = await api.post<ImportPreview>(`/spaces/${space.id}/imports/preview`, {
        ...form,
        expectedDraftRev,
      })
      if (isCurrent()) preview.value = result
    }),
  )
}
async function apply() {
  if (!canApply.value || !preview.value) return
  const id = preview.value.id
  await mutation.run(({ isCurrent }) =>
    draft.submit(async (expectedDraftRev) => {
      const next = await api.post<Space>(`/imports/${id}/apply`, { expectedDraftRev })
      if (!isCurrent()) return
      workspace.accept(next)
      preview.value = null
      confirmed.value = false
    }),
  )
}
async function readFile(event: Event) {
  const input = event.target as HTMLInputElement,
    file = input.files?.[0]
  if (!file) return
  await mutation.run(async ({ isCurrent }) => {
    const content = await file.text()
    if (isCurrent()) form.content = content
  })
  input.value = ''
}
async function exportTtl() {
  const space = workspace.space
  if (!space) return
  exported.value = ''
  await exporting.run(async ({ isCurrent }) => {
    const result = await api.get<{ content: string; format: 'TTL' }>(`/spaces/${space.id}/export`)
    if (isCurrent()) exported.value = result.content
  })
}
function download() {
  const url = URL.createObjectURL(new Blob([exported.value], { type: 'text/turtle;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = `${workspace.space?.code || 'ontology'}.ttl`
  link.click()
  URL.revokeObjectURL(url)
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>TTL / OWL 导入导出</h1>
      <p class="muted">先预览差异和不支持构造，人工确认后再写入草稿。</p>
    </div>
    <el-button :loading="exporting.pending.value" @click="exportTtl">读取 TTL 导出</el-button>
  </div>
  <section class="panel stack">
    <el-form label-position="top" @submit.prevent="makePreview">
      <div class="row">
        <el-form-item label="格式">
          <el-select v-model="form.kind" :disabled="mutation.pending.value || !workspace.canWrite">
            <el-option value="TTL" label="Turtle / TTL" />
            <el-option value="OWL" label="OWL / RDF XML" />
          </el-select>
        </el-form-item>
        <label v-if="workspace.canWrite" class="plain-button">
          选择本地文件
          <input
            type="file"
            accept=".ttl,.owl,.rdf,.xml,text/turtle,application/rdf+xml"
            :disabled="mutation.pending.value"
            class="file-input"
            @change="readFile"
          />
        </label>
      </div>
      <el-form-item label="原始内容" required>
        <el-input
          v-model="form.content"
          type="textarea"
          :rows="14"
          :disabled="mutation.pending.value || !workspace.canWrite"
          spellcheck="false"
        />
      </el-form-item>
      <el-button
        v-if="workspace.canWrite"
        type="primary"
        native-type="submit"
        :loading="mutation.pending.value"
      >
        预览导入
      </el-button>
      <p v-else class="muted">当前账号只可查看和导出。</p>
    </el-form>
    <p v-if="draft.stale.value" class="error-text" role="alert">
      预览修订已过期；请保留原文并刷新空间后重新预览。
    </p>
    <p v-if="mutation.error.value" class="error-text" role="alert">{{ mutation.error.value }}</p>
    <section v-if="preview" class="stack">
      <div class="row spread">
        <h2>预览结果 · {{ preview.status }}</h2>
        <span class="pill">
          目标 r{{ preview.targetDraftRev }} · 新增 {{ preview.added }} · 更新 {{ preview.updated }}
        </span>
      </div>
      <p class="muted">
        未确认前草稿保持不变。不支持构造会保留原文进入候选；它们不代表已执行相应推理。
      </p>
      <section
        v-for="group in [
          { name: '语法 / 解析错误', items: preview.errors },
          { name: '导入冲突', items: preview.conflicts },
          { name: '不支持的构造（保留原文）', items: preview.unsupported },
        ]"
        :key="group.name"
      >
        <h3>{{ group.name }} · {{ group.items.length }}</h3>
        <el-table v-if="group.items.length" :data="group.items">
          <el-table-column prop="line" label="行" width="70" />
          <el-table-column prop="construct" label="构造" min-width="120" />
          <el-table-column prop="message" label="说明" min-width="240" />
        </el-table>
        <p v-else class="muted">无</p>
      </section>
      <el-checkbox
        v-model="confirmed"
        :disabled="
          preview.status !== 'PREVIEW' ||
          !!preview.errors.length ||
          !!preview.conflicts.length ||
          !workspace.canWrite ||
          mutation.pending.value
        "
      >
        我已核对差异、冲突和不支持构造，确认写入当前草稿
      </el-checkbox>
      <el-button
        type="primary"
        :disabled="!canApply"
        :loading="mutation.pending.value"
        @click="apply"
      >
        确认应用导入
      </el-button>
    </section>
  </section>
  <p v-if="exporting.error.value" class="error-text" role="alert">{{ exporting.error.value }}</p>
  <section v-if="exported" class="panel export-panel">
    <div class="row spread">
      <h2>导出内容 · TTL</h2>
      <el-button @click="download">下载 .ttl</el-button>
    </div>
    <el-input :model-value="exported" type="textarea" :rows="12" readonly />
  </section>
</template>
<style scoped>
.file-input {
  display: block;
  font-size: 12px;
  max-width: 220px;
  margin-top: 6px;
}
.export-panel {
  margin-top: 16px;
}
</style>
