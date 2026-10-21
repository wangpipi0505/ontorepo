<script setup lang="ts">
/** 操作详情内嵌的实例查询配置；切操作先撤销上一操作结果，每次读取建立独立修订。 */
import { computed, watch } from 'vue'
import type {
  AssistantDeclaration,
  DataQuery,
  MappingCollection,
  OntProperty,
  Operation,
  OperationDataQuery,
  Space,
} from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import { useDraftDocument } from '../p2/useDraftDocument'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import DataQueryForm from './DataQueryForm.vue'
const props = defineProps<{ operation: Operation }>()
const api = useSession().api,
  workspace = useWorkspace()
const editor = useDraftDocument(async (id) => {
  const operationId = props.operation.id
  const [query, mappings, properties, assistant] = await Promise.all([
    api.get<OperationDataQuery>(`/operations/${operationId}/data-query?spaceId=${id}`),
    api.get<MappingCollection>(`/mappings?spaceId=${id}`),
    api.get<OntProperty[]>(`/spaces/${id}/properties`),
    api.get<AssistantDeclaration>(`/operations/${operationId}/assistant?spaceId=${id}`),
  ])
  return { query, mappings, properties, assistant }
})
const data = computed(() => editor.resource.data.value?.items)
const inputParams = computed(() => {
  const value = data.value?.assistant.inputSchema?.properties
  return value && typeof value === 'object' && !Array.isArray(value) ? Object.keys(value) : []
})
async function load() {
  if (workspace.space?.id) await editor.load()
}
async function save(dataQuery: DataQuery | null) {
  const spaceId = workspace.space?.id,
    operationId = props.operation.id
  if (!spaceId) return
  if (
    await editor.save((expectedDraftRev) =>
      api.put<Space>(`/operations/${operationId}/data-query`, {
        spaceId,
        expectedDraftRev,
        dataQuery,
      }),
    )
  )
    await editor.load()
}
watch(
  () => props.operation.id,
  () => {
    editor.resource.reset()
    void load()
  },
  { immediate: true },
)
</script>
<template>
  <div class="stack">
    <div class="row spread">
      <p class="muted">
        {{ operation.name }}（<span class="mono">{{ operation.code }}</span
        >）· 数据查询
      </p>
      <el-button :disabled="editor.mutation.pending.value" @click="load">刷新查询配置</el-button>
    </div>
    <AsyncState
      :loading="editor.resource.loading.value"
      :error="editor.resource.error.value"
      @retry="load"
    >
      <DataQueryForm
        v-if="data"
        :key="`${operation.id}-${editor.resource.data.value?.space.draftRev}`"
        :value="data.query.dataQuery"
        :mappings="data.mappings"
        :properties="data.properties"
        :input-params="inputParams"
        :can-write="workspace.canWrite"
        :pending="editor.mutation.pending.value"
        :stale="editor.draft.stale.value"
        :error="editor.mutation.error.value"
        @save="save"
      />
    </AsyncState>
  </div>
</template>
