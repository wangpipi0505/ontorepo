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
interface GlobalData {
  knowledge: KnowledgeRow[]
  sources: SourceRow[]
}
const router = useRouter(),
  api = useSession().api,
  data = useResource<GlobalData | null>(null),
  spaces = useResource<Space[]>([])
const spaceId = ref(''),
  keyword = ref(''),
  tab = ref<'knowledge' | 'sources'>('knowledge')
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
