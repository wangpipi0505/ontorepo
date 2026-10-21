<script setup lang="ts">
/** 预览写入候选批次，人工确认才写模型；两步都使用读取时的空间修订。 */
import { computed, onMounted, ref } from 'vue'
import type {
  Datasource,
  ExtractionRequest,
  ManagedFile,
  OntClass,
  Space,
  Suggestion,
  SuggestionDecision,
} from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useDraftDocument } from '../p2/useDraftDocument'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import ExtractionForm from './ExtractionForm.vue'
import SuggestionReview from './SuggestionReview.vue'
const api = useSession().api,
  batchId = ref(''),
  batchInput = ref('')
const editor = useDraftDocument(async (id) => {
  const [suggestions, sources, files, classes] = await Promise.all([
    api.get<Suggestion[]>(
      `/suggestions?spaceId=${id}${batchId.value ? `&batchId=${encodeURIComponent(batchId.value)}` : ''}`,
    ),
    api.get<Datasource[]>(`/datasources?spaceId=${id}`),
    api.get<ManagedFile[]>(`/data-files?spaceId=${id}`),
    api.get<OntClass[]>(`/classes?spaceId=${id}`),
  ])
  return { suggestions, sources, files, classes }
})
const data = computed(() => editor.resource.data.value?.items)
async function load() {
  if (!editor.mutation.pending.value) {
    batchId.value = batchInput.value.trim()
    await editor.load()
  }
}
async function extract(value: ExtractionRequest) {
  const spaceId = editor.workspace.space?.id
  if (!spaceId) return
  let result: { space: Space; batchId: string; count: number } | undefined
  if (
    await editor.save(async (expectedDraftRev) => {
      result = await api.post<{ space: Space; batchId: string; count: number }>(
        '/suggestions/extract',
        { ...value, spaceId, expectedDraftRev },
      )
      return result.space
    })
  ) {
    if (result) batchInput.value = batchId.value = result.batchId
    await editor.load()
  }
}
async function decide(decisions: SuggestionDecision[]) {
  const spaceId = editor.workspace.space?.id
  if (!spaceId) return
  if (
    await editor.save((expectedDraftRev) =>
      api.post<Space>('/suggestions/decide', { spaceId, expectedDraftRev, decisions }),
    )
  )
    await editor.load()
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>三来源建模抽取</h1>
      <p class="muted">表结构、结构文件与文档的规则抽取、真实证据与人工确认。</p>
    </div>
    <el-button :disabled="editor.mutation.pending.value" @click="load">刷新候选</el-button>
  </div>
  <div class="stack">
    <p v-if="editor.mutation.error.value" class="error-text" role="alert">
      {{ editor.mutation.error.value }}
    </p>
    <AsyncState
      :loading="editor.resource.loading.value"
      :error="editor.resource.error.value"
      @retry="load"
    >
      <template v-if="data">
        <section class="panel">
          <h2>选择来源并预览</h2>
          <ExtractionForm
            :sources="data.sources"
            :files="data.files"
            :can-write="editor.workspace.canWrite"
            :pending="editor.mutation.pending.value"
            :stale="editor.draft.stale.value"
            @extract="extract"
          />
        </section>
        <section class="panel stack">
          <h2>人工审阅与批量确认</h2>
          <el-form label-position="top" @submit.prevent="load">
            <el-form-item label="批次筛选（留空读取全部）">
              <el-input v-model="batchInput" :disabled="editor.mutation.pending.value" />
            </el-form-item>
            <el-button native-type="submit" :disabled="editor.mutation.pending.value">
              读取批次
            </el-button>
          </el-form>
          <SuggestionReview
            :key="`${batchId}-${editor.resource.data.value?.space.draftRev}`"
            :items="data.suggestions"
            :classes="data.classes"
            :can-write="editor.workspace.canWrite"
            :pending="editor.mutation.pending.value"
            :stale="editor.draft.stale.value"
            :error="editor.mutation.error.value"
            @decide="decide"
          />
        </section>
      </template>
    </AsyncState>
  </div>
</template>
