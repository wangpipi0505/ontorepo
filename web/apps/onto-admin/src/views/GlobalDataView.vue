<script setup lang="ts">
/** 知识与数据全局目录：知识整理与数据源登记按所属本体分组展示；明细仍进入具体本体。 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { Space } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import PagerBar from '@onto/shared-ui/PagerBar.vue'
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
  description?: string
  ownerName?: string
  snapshotCount?: number
  lastSnapshotAt?: string
  mappingCount?: number
  accessScope?: string[]
}
interface SourceGroup {
  spaceId: string
  spaceName: string
  sources: SourceRow[]
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
  targetName?: string
  [key: string]: unknown
}
interface GlobalData {
  knowledge: KnowledgeRow[]
  sourceGroups: SourceGroup[]
  mappings: MappingCountRow[]
  mappingRows: MappingDetailRow[]
}
const router = useRouter(),
  api = useSession().api,
  data = useResource<GlobalData | null>(null),
  spaces = useResource<Space[]>([])
const spaceId = ref(''),
  keyword = ref('')
const pageSize = 30
const pages = reactive<Record<string, number>>({
  knowledge: 1,
  mappings: 1,
})
function pageSlice<T>(key: string, rows: T[]) {
  const start = ((pages[key] ?? 1) - 1) * pageSize
  return rows.slice(start, start + pageSize)
}
function resetPages() {
  for (const key of Object.keys(pages)) pages[key] = 1
}
watch([spaceId, keyword], resetPages)
const tab = ref<'knowledge' | 'sources' | 'mappings'>('knowledge')
const spaceNames = computed(
  () => new Map(spaces.data.value.map((space) => [space.id, space.name])),
)
function sourceGroups(): SourceGroup[] {
  const text = keyword.value.trim().toLocaleLowerCase()
  return (data.data.value?.sourceGroups ?? [])
    .map((group) => ({
      ...group,
      sources: group.sources.filter((item) => matchSource(item, text)),
    }))
    .filter((group) => (!spaceId.value || group.spaceId === spaceId.value) && group.sources.length > 0)
}
function matchSource(item: SourceRow, text: string) {
  if (!text) return true
  return `${item.name} ${item.code} ${item.kind} ${item.dialect ?? ''} ${item.description ?? ''} ${
    item.spaceName ?? spaceNames.value.get(item.spaceId) ?? ''
  }`
    .toLocaleLowerCase()
    .includes(text)
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
const kindLabels: Record<string, string> = {
  RELATIONAL: '关系库',
  WAREHOUSE: '数仓',
  LAKE: '数据湖',
  FILE: '文件',
  HTTP: 'HTTP 接口',
  DOCUMENT: '文档库',
  KEY_VALUE: '键值存储',
  WIDE_COLUMN: '宽列',
  GRAPH: '图库',
}
function kindFormat(kind?: string, dialect?: string) {
  const label = kindLabels[kind ?? ''] ?? kind ?? '—'
  return dialect ? `${label} · ${dialect}` : label
}
const statusLabels: Record<string, string> = {
  UNTESTED: '未测试',
  SUCCEEDED: '已连通',
  FAILED: '连接失败',
}
function statusLabel(status?: string) {
  return status ? (statusLabels[status] ?? status) : '—'
}
function whenText(value?: string) {
  return value ? String(value).replace('T', ' ').slice(0, 16) : '—'
}
function syncText(row: SourceRow) {
  return row.snapshotCount ? `${row.snapshotCount} 份 · ${whenText(row.lastSnapshotAt)}` : '未同步'
}
function scopeLines(scope: unknown) {
  if (Array.isArray(scope)) return scope.map((item) => String(item))
  if (typeof scope === 'string' && scope.trim()) {
    try {
      const parsed: unknown = JSON.parse(scope)
      return Array.isArray(parsed) ? parsed.map((item) => String(item)) : [scope]
    } catch {
      return [scope]
    }
  }
  return []
}
const enumLabels: Record<string, string> = {
  CLASS: '对象',
  PROPERTY: '属性',
  RELATION: '关系',
  TTL: 'TTL',
  OWL: 'OWL',
  PENDING: '待处理',
  APPLIED: '已应用',
  VALIDATED: '已校验',
  FAILED: '失败',
  SUCCESS: '成功',
  ACTIVE: '启用',
  INACTIVE: '停用',
  DISABLED: '停用',
}
function enumLabel(value?: string) {
  return value ? (enumLabels[value] ?? value) : '—'
}
function mappingSource(row: MappingDetailRow) {
  return [row.sourceResource, row.sourceField].filter(Boolean).join(' · ') || '—'
}
function mappingTarget(row: MappingDetailRow) {
  if (row.targetName) return row.targetName
  return [row.targetClassId, row.targetPropertyId, row.relationId].filter(Boolean).join(' · ') || '—'
}
const activeRows = computed(() => {
  switch (tab.value) {
    case 'knowledge':
      return knowledgeRows()
    default:
      return mappingDetailRows()
  }
})
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
        <p class="muted">跨本体登记目录：知识整理与数据源登记统一检索，按所属本体分组。</p>
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
          <el-tab-pane :label="`知识管理 ${knowledgeRows().length}`" name="knowledge">
            <el-table :data="pageSlice('knowledge', knowledgeRows())" row-key="id">
              <el-table-column prop="term" label="术语 / 实体" min-width="180" />
              <el-table-column label="类型" width="120">
                <template #default="{ row }">{{ enumLabel(row.kind) }}</template>
              </el-table-column>
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
          <el-tab-pane :label="`数据源 ${sourceGroups().reduce((n, g) => n + g.sources.length, 0)}`" name="sources">
            <p class="muted group-hint">
              数据源按所属本体分组展示登记清单；同一数据连接可被多个本体分别登记使用，
              展开行可查看接入范围与元数据快照情况。
            </p>
            <div v-if="!sourceGroups().length">
              <el-empty description="无匹配的数据源登记" />
            </div>
            <div v-for="group in sourceGroups()" :key="group.spaceId" class="source-group">
              <h3 class="group-title">
                {{ group.spaceName }}
                <span class="group-count">{{ group.sources.length }} 个登记</span>
              </h3>
              <el-table :data="group.sources" row-key="id">
                <el-table-column type="expand">
                  <template #default="{ row }">
                    <div class="source-detail">
                      <p>
                        编码：<span class="mono">{{ row.code }}</span>
                        · 标识：<span class="mono">{{ row.id.slice(0, 8) }}</span>
                      </p>
                      <p>
                        接入范围（2.3② 数据接入）：
                        {{ scopeLines(row.accessScope).join('；') || '未声明' }}
                      </p>
                      <p>
                        元数据快照（2.3⑥ 元数据同步）：
                        共 {{ row.snapshotCount ?? 0 }} 份 · 最近 {{ whenText(row.lastSnapshotAt) }}
                      </p>
                      <p>说明：{{ row.description || '—' }}</p>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column prop="name" label="名称" min-width="170" />
                <el-table-column label="类别 · 格式" width="180">
                  <template #default="{ row }">{{ kindFormat(row.kind, row.dialect) }}</template>
                </el-table-column>
                <el-table-column label="说明" min-width="180" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.description || '—' }}</template>
                </el-table-column>
                <el-table-column label="维护主体" width="120">
                  <template #default="{ row }">{{ row.ownerName || '—' }}</template>
                </el-table-column>
                <el-table-column label="可用状态" width="100">
                  <template #default="{ row }">{{ statusLabel(row.status) }}</template>
                </el-table-column>
                <el-table-column label="最近连接测试" width="130">
                  <template #default="{ row }">{{ whenText(row.lastTestAt) }}</template>
                </el-table-column>
                <el-table-column label="元数据同步" width="160">
                  <template #default="{ row }">{{ syncText(row) }}</template>
                </el-table-column>
                <el-table-column label="被引用映射" width="110">
                  <template #default="{ row }">{{ row.mappingCount ?? 0 }} 条</template>
                </el-table-column>
                <el-table-column label="操作" width="120" fixed="right">
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
            </div>
          </el-tab-pane>
          <el-tab-pane :label="`映射 ${mappingDetailRows().length}`" name="mappings">
            <el-table :data="pageSlice('mappings', mappingDetailRows())" row-key="id">
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
        </el-tabs>
        <PagerBar
          v-if="tab !== 'sources'"
          :key="tab"
          :page="pages[tab] ?? 1"
          :total="activeRows.length"
          :size="pageSize"
          @update:page="pages[tab] = $event"
        />
      </AsyncState>
    </section>
  </section>
</template>
<style scoped>
.global-filter {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}
.group-hint {
  margin: 0 0 10px;
}
.source-group {
  margin-bottom: 18px;
}
.group-title {
  margin: 0 0 8px;
  font-size: 14px;
}
.group-count {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-weight: normal;
}
.source-detail {
  padding: 4px 12px;
  line-height: 1.9;
}
.source-detail p {
  margin: 0;
}
.mono {
  font-family: monospace;
}
</style>
