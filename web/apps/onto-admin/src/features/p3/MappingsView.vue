<script setup lang="ts">
/** 三层工作台的读基线固定到一致性快照；变更先读取影响，再打开可审查表单。 */
import { computed, nextTick, onMounted, ref, shallowRef } from 'vue'
import type {
  Codebook,
  Datasource,
  Mapping,
  MappingCollection,
  MappingImpact,
  MappingKind,
  MappingValidation,
  OntClass,
  OntProperty,
  Relation,
  Space,
} from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useDraftDocument } from '../p2/useDraftDocument'
import { useResource } from '../../composables/useResource'
import { confirmAction } from '../../composables/useMutation'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import MappingForm from './MappingForm.vue'
import MappingCoverage from './MappingCoverage.vue'
import MappingList from './MappingList.vue'
import MappingWorkbench from './MappingWorkbench.vue'
import type { MappingEdge, MappingPrefill } from './mappingGraph'
const api = useSession().api
const editor = useDraftDocument(async (id) => {
  const [mappings, sources, classes, properties, relations, codebooks, validation] =
    await Promise.all([
      api.get<MappingCollection>(`/mappings?spaceId=${id}`),
      api.get<Datasource[]>(`/datasources?spaceId=${id}`),
      api.get<OntClass[]>(`/classes?spaceId=${id}`),
      api.get<OntProperty[]>(`/spaces/${id}/properties`),
      api.get<Relation[]>(`/relations?spaceId=${id}`),
      api.get<Codebook[]>(`/codebooks?spaceId=${id}`),
      api.get<MappingValidation>(`/mappings/validate?spaceId=${id}`),
    ])
  return { mappings, sources, classes, properties, relations, codebooks, validation }
})
const data = computed(() => editor.resource.data.value?.items),
  kind = ref<MappingKind>('objects')
const open = ref(false),
  editing = shallowRef<Mapping | null>(null),
  impact = useResource<MappingImpact | null>(null)
const prefill = shallowRef<MappingPrefill | undefined>(),
  formPanel = ref<HTMLElement | null>(null)
const labels: Record<MappingKind, string> = {
  objects: '对象映射',
  properties: '属性映射',
  relations: '关系映射',
}
async function load() {
  if (!open.value && !editor.mutation.pending.value) {
    impact.reset()
    await editor.load()
  }
}
async function inspect(item: Mapping | null, seed?: MappingPrefill) {
  if (!editor.resource.data.value || !editor.workspace.canWrite) return
  prefill.value = seed
  editing.value = item
  open.value = true
  impact.reset()
  editor.mutation.error.value = ''
  if (item) {
    const spaceId = editor.workspace.space?.id,
      currentKind = kind.value
    await impact.load(() =>
      api.get<MappingImpact>(
        `/mappings/impact?spaceId=${spaceId}&kind=${currentKind}&id=${item.id}`,
      ),
    )
  }
}
async function prefillFromGraph(value: MappingPrefill) {
  if (open.value || !editor.workspace.canWrite || editor.draft.stale.value) return
  kind.value = value.kind
  await inspect(null, value)
  await nextTick()
  formPanel.value?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' })
}
async function inspectFromGraph(edge: MappingEdge) {
  if (open.value || !editor.workspace.canWrite) return
  kind.value = edge.kind
  await inspect(edge.item)
  await nextTick()
  formPanel.value?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' })
}
async function save(value: Record<string, unknown>) {
  const id = editor.workspace.space?.id,
    itemId = editing.value?.id,
    currentKind = kind.value
  if (!id || (editing.value && !impact.data.value)) return
  if (
    await editor.save((expectedDraftRev) =>
      itemId
        ? api.put<Space>(`/mappings/${currentKind}/${itemId}`, {
            ...value,
            spaceId: id,
            expectedDraftRev,
          })
        : api.post<Space>(`/mappings/${currentKind}`, { ...value, spaceId: id, expectedDraftRev }),
    )
  ) {
    open.value = false
    await editor.load()
  }
}
async function remove() {
  const id = editor.workspace.space?.id,
    itemId = editing.value?.id,
    currentKind = kind.value,
    revision = editor.resource.data.value?.space.draftRev
  const context = editor.workspace.captureContext()
  if (!id || !itemId || revision === undefined || !impact.data.value || !editor.workspace.canWrite)
    return
  if (!(await confirmAction('删除当前映射？请先检查上方受影响操作与引用问题。'))) return
  if (!editor.workspace.isCurrentContext(context)) return
  if (
    await editor.save(() =>
      api.delete<Space>(
        `/mappings/${currentKind}/${itemId}?spaceId=${id}&expectedDraftRev=${revision}`,
      ),
    )
  ) {
    open.value = false
    await editor.load()
  }
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>三层映射工作台</h1>
      <p class="muted">元数据资源与本体模型的对象、属性、关系映射。</p>
    </div>
    <el-button :disabled="open || editor.mutation.pending.value" @click="load">刷新映射</el-button>
  </div>
  <AsyncState
    :loading="editor.resource.loading.value"
    :error="editor.resource.error.value"
    @retry="load"
  >
    <div v-if="data" class="stack">
      <MappingWorkbench
        :sources="data.sources"
        :mappings="data.mappings"
        :classes="data.classes"
        :properties="data.properties"
        :relations="data.relations"
        :can-write="editor.workspace.canWrite"
        :disabled="open || editor.draft.stale.value"
        @prefill="prefillFromGraph"
        @edit="inspectFromGraph"
      />
      <MappingList
        v-model:kind="kind"
        :mappings="data.mappings"
        :classes="data.classes"
        :properties="data.properties"
        :relations="data.relations"
        :can-write="editor.workspace.canWrite"
        :disabled="open"
        @edit="inspect"
      />
      <section v-if="open" ref="formPanel" class="panel stack">
        <h2>{{ editing ? '编辑' : '新增' }}{{ labels[kind] }}</h2>
        <p v-if="prefill" class="readonly-note">
          连线预填尚未保存。请审查来源、目标、标识模板及转换规则，再保存映射。
        </p>
        <AsyncState
          :loading="impact.loading.value"
          :error="impact.error.value"
          @retry="inspect(editing)"
        >
          <div v-if="impact.data.value">
            <h3>变更前影响</h3>
            <p>
              操作：{{
                impact.data.value.operations
                  .map((item) => `${item.name} (${item.code})`)
                  .join('、') || '无'
              }}
            </p>
            <p
              v-for="issue in impact.data.value.issues"
              :key="`${issue.code}-${issue.entityId}`"
              class="error-text"
            >
              {{ issue.message }} · {{ issue.entityId }} {{ issue.field }}
            </p>
          </div>
          <MappingForm
            v-if="!editing || impact.data.value"
            :key="`${kind}-${editing?.id || 'new'}-${prefill ? 'prefill' : 'manual'}`"
            :kind="kind"
            :value="editing"
            :prefill="prefill"
            :sources="data.sources"
            :mappings="data.mappings"
            :classes="data.classes"
            :properties="data.properties"
            :relations="data.relations"
            :codebooks="data.codebooks"
            :can-write="editor.workspace.canWrite"
            :pending="editor.mutation.pending.value"
            :stale="editor.draft.stale.value"
            :error="editor.mutation.error.value"
            @save="save"
            @cancel="open = false"
          />
          <el-button
            v-if="editing"
            type="danger"
            :disabled="
              !editor.workspace.canWrite || editor.mutation.pending.value || !impact.data.value
            "
            @click="remove"
          >
            删除映射
          </el-button>
        </AsyncState>
        <el-button v-if="impact.error.value" @click="open = false">关闭影响预览</el-button>
      </section>
      <MappingCoverage :value="data.validation" />
    </div>
  </AsyncState>
</template>
