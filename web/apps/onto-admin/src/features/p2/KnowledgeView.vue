<script setup lang="ts">
/** 知识列表与同空间概念同批读取并核对修订；冻结后的知识只在候选供给页查看。 */
import { computed, onMounted, ref, shallowRef } from 'vue'
import type { KnowledgeEntry, OntClass, Space } from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useDraftDocument } from './useDraftDocument'
import { useDraftRevision } from '../../composables/useDraftRevision'
import { confirmAction } from '../../composables/useMutation'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import KnowledgeForm, { type KnowledgeDraft } from './KnowledgeForm.vue'
const api = useSession().api
const editor = useDraftDocument(async (id) => {
  const [entries, concepts] = await Promise.all([
    api.get<KnowledgeEntry[]>(`/knowledge?spaceId=${id}`),
    api.get<OntClass[]>(`/classes?spaceId=${id}`),
  ])
  return { entries, concepts }
})
const items = computed(() => editor.resource.data.value?.items.entries || [])
const concepts = computed(() =>
  (editor.resource.data.value?.items.concepts || []).filter(
    (item) => item.spaceId === editor.workspace.space?.id,
  ),
)
const open = ref(false),
  editing = shallowRef<KnowledgeEntry | null>(null),
  draft = useDraftRevision()
// 输入归页面所有，弹窗内部过渡或权限更新导致子组件重建时仍保留用户编辑。
const form = ref<KnowledgeDraft>({
  term: '',
  aliases: '',
  definition: '',
  sourceRef: '',
  conceptRef: '',
})
async function load() {
  open.value = false
  await editor.load()
}
function edit(item: KnowledgeEntry | null) {
  const snapshot = editor.resource.data.value
  if (!editor.workspace.canWrite || !snapshot) return
  editing.value = item
  form.value = {
    term: item?.term || '',
    aliases: item?.aliases.join('\n') || '',
    definition: item?.definition || '',
    sourceRef: item?.sourceRef || '',
    conceptRef: item?.conceptRef || '',
  }
  draft.start(snapshot.space.draftRev)
  editor.mutation.error.value = ''
  open.value = true
}
async function save(body: Omit<KnowledgeEntry, 'id' | 'spaceId'>) {
  if (!editor.workspace.canWrite || draft.stale.value) return
  const id = editor.workspace.space?.id
  if (!id) return
  const ok = await editor.mutation.run(({ isCurrent }) =>
    draft.submit(async (expectedDraftRev) => {
      const request = { ...body, spaceId: id, expectedDraftRev }
      const next = editing.value
        ? await api.put<Space>(`/knowledge/${editing.value.id}`, request)
        : await api.post<Space>('/knowledge', request)
      if (isCurrent()) editor.workspace.accept(next)
    }),
  )
  if (ok) await load()
}
async function remove(item: KnowledgeEntry) {
  const snapshot = editor.resource.data.value
  if (!snapshot || !editor.workspace.canWrite) return
  // 删除确认期间工作区可能变化，始终提交用户点击此列表时看到的修订。
  const expectedDraftRev = snapshot.space.draftRev,
    spaceId = snapshot.space.id
  if (!(await confirmAction(`删除知识“${item.term}”？已冻结候选中的知识仍会保留。`))) return
  const ok = await editor.mutation.run(async ({ isCurrent }) => {
    const next = await api.delete<Space>(
      `/knowledge/${item.id}?spaceId=${spaceId}&expectedDraftRev=${expectedDraftRev}`,
    )
    if (isCurrent()) editor.workspace.accept(next)
  })
  if (ok) await load()
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>知识管理</h1>
      <p class="muted">维护术语、别名、定义与来源，随候选冻结后用于检索和匹配。</p>
    </div>
    <div class="row">
      <el-button :disabled="editor.mutation.pending.value" @click="load">刷新知识</el-button>
      <el-button
        v-if="editor.workspace.canWrite"
        :disabled="!editor.resource.data.value"
        type="primary"
        @click="edit(null)"
      >
        新建知识
      </el-button>
    </div>
  </div>
  <p v-if="editor.mutation.error.value && !open" class="error-text" role="alert">
    {{ editor.mutation.error.value }}
  </p>
  <AsyncState
    :loading="editor.resource.loading.value"
    :error="editor.resource.error.value"
    :empty="!items.length"
    empty-text="还没有知识条目。添加术语、定义和可追溯来源。"
    @retry="load"
  >
    <section class="panel overflow">
      <el-table :data="items">
        <el-table-column prop="term" label="术语" min-width="130" />
        <el-table-column label="别名" min-width="150">
          <template #default="{ row }">{{ row.aliases.join('、') || '—' }}</template>
        </el-table-column>
        <el-table-column prop="definition" label="定义" min-width="220" />
        <el-table-column prop="sourceRef" label="来源" min-width="150" />
        <el-table-column v-if="editor.workspace.canWrite" label="维护" width="135">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :disabled="editor.mutation.pending.value"
              @click="edit(row)"
            >
              编辑
            </el-button>
            <el-button
              link
              type="danger"
              :disabled="editor.mutation.pending.value"
              @click="remove(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </AsyncState>
  <el-dialog
    v-model="open"
    destroy-on-close
    title="维护知识"
    width="700px"
    :close-on-click-modal="false"
    :show-close="!editor.mutation.pending.value"
    :close-on-press-escape="!editor.mutation.pending.value"
  >
    <KnowledgeForm
      v-if="open"
      v-model="form"
      :concepts="concepts"
      :pending="editor.mutation.pending.value"
      :can-write="editor.workspace.canWrite"
      :stale="draft.stale.value"
      :error="editor.mutation.error.value"
      @save="save"
      @cancel="open = false"
    />
  </el-dialog>
</template>
