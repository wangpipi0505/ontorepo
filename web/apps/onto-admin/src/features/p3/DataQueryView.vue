<script setup lang="ts">
/** 切操作先撤销上一操作配置；每次读取建立独立修订，409 不自动换基线。 */
import { computed, onMounted, ref, watch } from 'vue'
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
import { useResource } from '../../composables/useResource'
import { useDraftDocument } from '../p2/useDraftDocument'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import DataQueryForm from './DataQueryForm.vue'
const api = useSession().api,
  workspace = useWorkspace(),
  selected = ref(''),
  operations = useResource<Operation[]>([])
const editor = useDraftDocument(async (id) => {
  const operationId = selected.value
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
async function loadOperations() {
  const id = workspace.space?.id
  if (id) await operations.load(() => api.get<Operation[]>(`/operations?spaceId=${id}`))
}
async function refresh() {
  await loadOperations()
  if (selected.value) await editor.load()
}
watch(selected, () => {
  editor.resource.reset()
  if (selected.value) void editor.load()
})
async function save(dataQuery: DataQuery | null) {
  const spaceId = workspace.space?.id,
    operationId = selected.value
  if (!spaceId || !operationId) return
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
onMounted(loadOperations)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>操作数据查询</h1>
      <p class="muted">为现有模型操作绑定冻结映射与参数化过滤。</p>
    </div>
    <el-button :disabled="editor.mutation.pending.value" @click="refresh">刷新查询配置</el-button>
  </div>
  <section class="panel stack">
    <AsyncState
      :loading="operations.loading.value"
      :error="operations.error.value"
      @retry="loadOperations"
    >
      <el-form label-position="top">
        <el-form-item label="草稿操作">
          <el-select v-model="selected" :disabled="editor.mutation.pending.value">
            <el-option
              v-for="item in operations.data.value"
              :key="item.id"
              :value="item.id"
              :label="`${item.name} (${item.code})`"
            />
          </el-select>
        </el-form-item>
      </el-form>
    </AsyncState>
    <AsyncState
      v-if="selected"
      :loading="editor.resource.loading.value"
      :error="editor.resource.error.value"
      @retry="editor.load"
    >
      <DataQueryForm
        v-if="data"
        :key="`${selected}-${editor.resource.data.value?.space.draftRev}`"
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
  </section>
</template>
