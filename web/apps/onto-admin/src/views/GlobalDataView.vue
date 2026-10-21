<script setup lang="ts">
/** 知识与数据全局库：跨当前用户可见本体聚合，可按本体筛选；明细仍进入具体本体。 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { Space } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useSession } from '../stores/session'
import { useResource } from '../composables/useResource'
interface KnowledgeRow {
  id: string
  spaceId: string
  spaceName?: string
  kind?: string
  term: string
  definition?: string
  sourceRef?: string
}
interface SourceRow {
  id: string
  spaceId: string
  spaceName?: string
  code: string
  name: string
  kind: string
  dialect?: string
  status?: string
}
interface MappingCountRow {
  spaceId: string
  spaceName: string
  objects: number
  properties: number
  relations: number
}
interface MappingDetailRow {
  id: string
  spaceId: string
  spaceName: string
  category: string
  sourceResource?: string
  sourceField?: string
  targetClassId?: string
  targetPropertyId?: string
  relationId?: string
  [key: string]: unknown
}
interface SnapshotRow {
  id: string
  spaceId: string
  spaceName: string
  datasourceId: string
  sha256: string
  capturedAt?: string
}
interface InventoryRow {
  spaceId: string
  spaceName: string
  pendingSuggestions: number
  imports: number
}
interface SuggestionRow {
  id: string
  spaceId: string
  spaceName: string
  kind: string
  status: string
  confidence: number
  createdAt?: string
}
interface ImportRow {
  id: string
  spaceId: string
  spaceName: string
  kind: string
  status: string
  applied: boolean
  targetDraftRev: number
}
interface GlobalData {
  knowledge: KnowledgeRow[]
  sources: SourceRow[]
  mappings: MappingCountRow[]
  mappingRows: MappingDetailRow[]
  snapshots: SnapshotRow[]
  inventory: InventoryRow[]
  suggestions: SuggestionRow[]
  imports: ImportRow[]
}
const router = useRouter(),
  api = useSession().api,
  data = useResource<GlobalData | null>(null),
  spaces = useResource<Space[]>([])
const spaceId = ref(''),
  keyword = ref(''),
  tab = ref<'knowledge' | 'sources' | 'mappings' | 'snapshots' | 'inventory' | 'suggestions' | 'imports'>('knowledge')
const spaceNames = computed(
  () => new Map(spaces.data.value.map((space) => [space.id, space.name])),
)
function sourceRows() {
  const text = keyword.value.trim().toLocaleLowerCase()
  return (data.data.value?.sources ?? [])
    .filter((item) => !spaceId.value || item.spaceId === spaceId.value)
    .filter(
      (item) =>
        !text ||
        `${item.name} ${item.code} ${item.kind} ${spaceNames.value.get(item.spaceId) ?? ''}`
          .toLocaleLowerCase()
          .includes(text),
    )
}
function knowledgeRows() {
  const text = keyword.value.trim().toLocaleLowerCase()
  return (data.data.value?.knowledge ?? [])
    .filter((item) => !spaceId.value || item.spaceId === spaceId.value)
    .filter(
      (item) =>
        !text ||
        `${item.term} ${item.kind ?? ''} ${item.definition ?? ''} ${
          spaceNames.value.get(item.spaceId) ?? ''
        }`
          .toLocaleLowerCase()
          .includes(text),
    )
}

function mappingDetailRows() {
  const text = keyword.value.trim().toLocaleLowerCase()
  return (data.data.value?.mappingRows ?? [])
    .filter((item) => !spaceId.value || item.spaceId === spaceId.value)
    .filter((item) => !text || item.spaceName.toLocaleLowerCase().includes(text))
}
function mappingCategory(category: string) {
  return category === 'objects' ? '对象' : category === 'properties' ? '属性' : '关系'
}
function mappingSource(row: MappingDetailRow) {
  return [row.sourceResource, row.sourceField].filter(Boolean).join(' · ') || '—'
}
function mappingTarget(row: MappingDetailRow) {
  return [row.targetClassId, row.targetPropertyId, row.relationId].filter(Boolean).join(' · ') || '—'
}
function snapshotRows() {
  const text = keyword.value.trim().toLocaleLowerCase()
  return (data.data.value?.snapshots ?? [])
    .filter((item) => !spaceId.value || item.spaceId === spaceId.value)
    .filter((item) => !text || item.spaceName.toLocaleLowerCase().includes(text))
}
function inventoryRows() {
  const text = keyword.value.trim().toLocaleLowerCase()
  return (data.data.value?.inventory ?? [])
    .filter((item) => !spaceId.value || item.spaceId === spaceId.value)
    .filter((item) => !text || item.spaceName.toLocaleLowerCase().includes(text))
}
function suggestionRows() {
  const text = keyword.value.trim().toLocaleLowerCase()
  return (data.data.value?.suggestions ?? [])
    .filter((item) => !spaceId.value || item.spaceId === spaceId.value)
    .filter((item) => !text || item.spaceName.toLocaleLowerCase().includes(text))
}
function importRows() {
  const text = keyword.value.trim().toLocaleLowerCase()
  return (data.data.value?.imports ?? [])
    .filter((item) => !spaceId.value || item.spaceId === spaceId.value)
    .filter((item) => !text || item.spaceName.toLocaleLowerCase().includes(text))
}
async function load() {
  await Promise.all([
    data.load(() => api.get<GlobalData>('/global/data')),
    spaces.load(() => api.get<Space[]>('/spaces')),
  ])
}
onMounted(load)
</script>
<template>
  <section class="module-page">
    <header class="module-heading">
      <div>
        <h1>知识与数据</h1>
        <p class="muted">跨本体物料库：术语、概念卡、知识实体、事实关系与数据源统一检索。</p>
      </div>
      <el-button @click="load">刷新全局库</el-button>
    </header>
    <section class="panel">
      <div class="global-filter">
        <el-select v-model="spaceId" placeholder="全部本体" clearable>
          <el-option label="全部本体" value="" />
          <el-option
            v-for="space in spaces.data.value"
            :key="space.id"
            :label="space.name"
            :value="space.id"
          />
        </el-select>
        <el-input v-model="keyword" placeholder="搜术语 / 数据源 / 本体内" clearable />
        <el-button @click="load">刷新</el-button>
      </div>
      <AsyncState
        :loading="data.loading.value || spaces.loading.value"
        :error="data.error.value || spaces.error.value"
        @retry="load"
      >
        <el-tabs v-model="tab">
          <el-tab-pane :label="`知识物料 ${knowledgeRows().length}`" name="knowledge">
            <el-table :data="knowledgeRows()" row-key="id">
              <el-table-column prop="term" label="术语 / 实体" min-width="180" />
              <el-table-column prop="kind" label="类型" width="120" />
              <el-table-column prop="definition" label="定义" min-width="320" show-overflow-tooltip />
              <el-table-column label="所属本体" width="180">
                <template #default="{ row }">
                  {{ row.spaceName || spaceNames.get(row.spaceId) || row.spaceId }}
                </template>
              </el-table-column>
              <el-table-column label="操作" width="130">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    @click="router.push(`/data?spaceId=${row.spaceId}&tab=knowledge`)"
                  >
                    进入本体
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`数据源 ${sourceRows().length}`" name="sources">
            <el-table :data="sourceRows()" row-key="id">
              <el-table-column prop="code" label="编码" width="150" />
              <el-table-column prop="name" label="名称" min-width="180" />
              <el-table-column prop="kind" label="类型" width="130" />
              <el-table-column prop="dialect" label="方言" width="120" />
              <el-table-column label="所属本体" width="180">
                <template #default="{ row }">
                  {{ row.spaceName || spaceNames.get(row.spaceId) || row.spaceId }}
                </template>
              </el-table-column>
              <el-table-column label="操作" width="130">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    @click="router.push(`/data?spaceId=${row.spaceId}&tab=datasources`)"
                  >
                    进入本体
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`映射 ${mappingDetailRows().length}`" name="mappings">
            <el-table :data="mappingDetailRows()" row-key="id">
              <el-table-column label="类别" width="80">
                <template #default="{ row }">{{ mappingCategory(row.category) }}</template>
              </el-table-column>
              <el-table-column label="来源" min-width="220">
                <template #default="{ row }">{{ mappingSource(row) }}</template>
              </el-table-column>
              <el-table-column label="目标" min-width="220">
                <template #default="{ row }">{{ mappingTarget(row) }}</template>
              </el-table-column>
              <el-table-column prop="spaceName" label="所属本体" min-width="200" />
              <el-table-column label="操作" width="140">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    @click="router.push(`/data?spaceId=${row.spaceId}&tab=mappings`)"
                  >
                    进入映射
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`快照 ${snapshotRows().length}`" name="snapshots">
            <el-table :data="snapshotRows()" row-key="id">
              <el-table-column label="快照" width="180">
                <template #default="{ row }">
                  <span class="mono">{{ row.id.slice(0, 8) }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="spaceName" label="所属本体" min-width="200" />
              <el-table-column prop="datasourceId" label="数据源" min-width="180" />
              <el-table-column prop="sha256" label="SHA-256" min-width="240" show-overflow-tooltip />
              <el-table-column prop="capturedAt" label="采集时间" width="180" />
              <el-table-column label="操作" width="140">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    @click="router.push(`/data?spaceId=${row.spaceId}&tab=datasources`)"
                  >
                    进入数据源
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`抽取/导入 ${inventoryRows().length}`" name="inventory">
            <el-table :data="inventoryRows()" row-key="spaceId">
              <el-table-column prop="spaceName" label="所属本体" min-width="240" />
              <el-table-column prop="pendingSuggestions" label="待确认抽取" width="130" />
              <el-table-column prop="imports" label="导入记录" width="120" />
              <el-table-column label="操作" width="160">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    @click="router.push(`/data?spaceId=${row.spaceId}&tab=suggestions`)"
                  >
                    进入抽取
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`抽取候选 ${suggestionRows().length}`" name="suggestions">
            <el-table :data="suggestionRows()" row-key="id">
              <el-table-column prop="spaceName" label="所属本体" min-width="200" />
              <el-table-column prop="kind" label="类型" width="110" />
              <el-table-column prop="status" label="状态" width="110" />
              <el-table-column prop="confidence" label="置信度" width="100" />
              <el-table-column prop="createdAt" label="创建时间" width="190" />
              <el-table-column label="操作" width="150">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    @click="router.push(`/data?spaceId=${row.spaceId}&tab=suggestions`)"
                  >
                    进入确认队列
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`导入记录 ${importRows().length}`" name="imports">
            <el-table :data="importRows()" row-key="id">
              <el-table-column prop="spaceName" label="所属本体" min-width="200" />
              <el-table-column prop="kind" label="格式" width="100" />
              <el-table-column prop="status" label="状态" width="110" />
              <el-table-column prop="targetDraftRev" label="目标修订" width="100" />
              <el-table-column label="已应用" width="90">
                <template #default="{ row }">{{ row.applied ? '是' : '否' }}</template>
              </el-table-column>
              <el-table-column label="操作" width="140">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    @click="router.push(`/data?spaceId=${row.spaceId}&tab=imports`)"
                  >
                    进入导入
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </AsyncState>
    </section>
  </section>
</template>
<style scoped>
.global-filter {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 12px;
}
.global-filter .el-select {
  width: 220px;
}
.global-filter .el-input {
  flex: 1 1 260px;
}
</style>
