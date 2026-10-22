<script setup lang="ts">
/** 测试与服务封装全局视图：跨可见本体汇总候选与正式版本。 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { Space } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import PagerBar from '@onto/shared-ui/PagerBar.vue'
import { useSession } from '../stores/session'
import { useResource } from '../composables/useResource'
import { publicationStatusLabels } from '../features/p4/publication'
import { releaseLabels } from '../features/overview/overview'
interface CandidateRow {
  id: string
  spaceId: string
  spaceName?: string
  draftRev: number
  status: string
  sha256: string
}
interface ReleaseRow {
  id: string
  spaceId: string
  spaceName?: string
  versionNo: number
  status: string
  publishedAt?: string
}
interface TestCaseRow {
  id: string
  spaceId: string
  spaceName?: string
  name: string
  question: string
  mode?: string
  matchMode?: string
  enabled: boolean
}
interface ServiceRow {
  id: string
  spaceId: string
  spaceName?: string
  releaseId: string
  versionNo: number
  opCode: string
  route: string
  description?: string
  status: string
  grantsCount?: number
  executionsCount?: number
}
interface GlobalDelivery {
  candidates: CandidateRow[]
  releases: ReleaseRow[]
  testCases: TestCaseRow[]
}
const router = useRouter(),
  api = useSession().api,
  data = useResource<GlobalDelivery | null>(null),
  services = useResource<ServiceRow[]>([]),
  spaces = useResource<Space[]>([])
const spaceFilter = ref(''),
  keyword = ref(''),
  tab = ref<'candidates' | 'releases' | 'testCases' | 'services'>('candidates')
const spaceNames = computed(() => new Map(spaces.data.value.map((item) => [item.id, item.name])))
const pageSize = 30
const pages = reactive<Record<string, number>>({ candidates: 1, releases: 1, testCases: 1, services: 1 })
function pageSlice<T>(key: string, rows: T[]) {
  const start = ((pages[key] ?? 1) - 1) * pageSize
  return rows.slice(start, start + pageSize)
}
function resetPages() {
  for (const key of Object.keys(pages)) pages[key] = 1
}
watch([spaceFilter, keyword], resetPages)
const caseModeLabels: Record<string, string> = { DIRECT: '直接', NL: '自然语言' }
const matchModeLabels: Record<string, string> = { EXACT: '精确', CONTAINS: '包含' }
function filterRows<T extends { spaceId: string; spaceName?: string }>(rows: T[]) {
  const text = keyword.value.trim().toLocaleLowerCase()
  return rows
    .filter((row) => !spaceFilter.value || row.spaceId === spaceFilter.value)
    .filter((row) =>
      `${spaceNames.value.get(row.spaceId) ?? row.spaceName ?? ''}`
        .toLocaleLowerCase()
        .includes(text),
    )
}
const candidates = computed(() => filterRows(data.data.value?.candidates ?? []))
const releases = computed(() => filterRows(data.data.value?.releases ?? []))
const testCases = computed(() => filterRows(data.data.value?.testCases ?? []))
const serviceRows = computed(() => services.data.value)
async function loadServices() {
  await services.load(() => api.get<ServiceRow[]>('/global/services'))
}
const activeRows = computed(() => {
  switch (tab.value) {
    case 'candidates':
      return candidates.value
    case 'releases':
      return releases.value
    case 'testCases':
      return testCases.value
    default:
      return serviceRows.value
  }
})
async function load() {
  await Promise.all([
    data.load(() => api.get<GlobalDelivery>('/global/delivery')),
    spaces.load(() => api.get<Space[]>('/spaces')),
  ])
}
onMounted(async () => {
  await load()
  await loadServices()
})
</script>
<template>
  <section class="module-page">
    <header class="module-heading">
      <div>
        <h1>测试与服务封装</h1>
        <p class="muted">跨本体查看冻结候选、发布门禁与正式版本；进入所属本体完成测试和发布。</p>
      </div>
      <el-button @click="load">刷新全局视图</el-button>
    </header>
    <section class="panel">
      <div class="global-filter">
        <el-select v-model="spaceFilter" placeholder="全部本体" clearable>
          <el-option label="全部本体" value="" />
          <el-option
            v-for="space in spaces.data.value"
            :key="space.id"
            :label="space.name"
            :value="space.id"
          />
        </el-select>
        <el-input v-model="keyword" placeholder="按本体内筛选" clearable />
        <el-button @click="load">刷新</el-button>
      </div>
      <AsyncState :loading="data.loading.value" :error="data.error.value" @retry="load">
        <el-tabs v-model="tab">
          <el-tab-pane :label="`候选 ${candidates.length}`" name="candidates">
            <el-table :data="pageSlice('candidates', candidates)" row-key="id">
              <el-table-column label="候选" width="180">
                <template #default="{ row }">
                  <span class="mono">{{ row.id.slice(0, 8) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="所属本体" min-width="180">
                <template #default="{ row }">
                  {{ row.spaceName || spaceNames.get(row.spaceId) || row.spaceId }}
                </template>
              </el-table-column>
              <el-table-column prop="draftRev" label="草稿修订" width="100" />
              <el-table-column label="状态" width="140">
                <template #default="{ row }">
                  {{ publicationStatusLabels[row.status] ?? row.status }}
                </template>
              </el-table-column>
              <el-table-column label="操作" width="150">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    @click="router.push(`/delivery?spaceId=${row.spaceId}&tab=testing`)"
                  >
                    进入测试与候选
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`正式版本 ${releases.length}`" name="releases">
            <el-table :data="pageSlice('releases', releases)" row-key="id">
              <el-table-column label="版本" width="100">
                <template #default="{ row }">v{{ row.versionNo }}</template>
              </el-table-column>
              <el-table-column label="所属本体" min-width="180">
                <template #default="{ row }">
                  {{ row.spaceName || spaceNames.get(row.spaceId) || row.spaceId }}
                </template>
              </el-table-column>
              <el-table-column label="状态" width="140">
                <template #default="{ row }">
                  {{ releaseLabels[row.status as keyof typeof releaseLabels] ?? row.status }}
                </template>
              </el-table-column>
              <el-table-column prop="publishedAt" label="发布时间" width="190" />
              <el-table-column label="操作" width="150">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    @click="router.push(`/delivery?spaceId=${row.spaceId}&tab=delivery`)"
                  >
                    进入发布与服务
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`用例 ${testCases.length}`" name="testCases">
            <el-table :data="pageSlice('testCases', testCases)" row-key="id">
              <el-table-column prop="name" label="用例" min-width="180" />
              <el-table-column prop="question" label="问题 / 输入" min-width="260" show-overflow-tooltip />
              <el-table-column label="模式" width="100">
                <template #default="{ row }">{{ caseModeLabels[row.mode ?? ''] ?? row.mode ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="匹配" width="100">
                <template #default="{ row }">{{ matchModeLabels[row.matchMode] ?? row.matchMode }}</template>
              </el-table-column>
              <el-table-column label="启用" width="80">
                <template #default="{ row }">{{ row.enabled ? '是' : '否' }}</template>
              </el-table-column>
              <el-table-column label="所属本体" min-width="180">
                <template #default="{ row }">
                  {{ row.spaceName || spaceNames.get(row.spaceId) || row.spaceId }}
                </template>
              </el-table-column>
              <el-table-column label="操作" width="150">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    @click="router.push(`/delivery?spaceId=${row.spaceId}&tab=testing`)"
                  >
                    进入用例
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`服务端点 ${serviceRows.length}`" name="services">
            <AsyncState
              :loading="services.loading.value"
              :error="services.error.value"
              :empty="!serviceRows.length"
              empty-text="当前可见版本尚未发布服务端点。"
              @retry="loadServices"
            >
              <el-table :data="pageSlice('services', serviceRows)" row-key="id">
                <el-table-column label="版本" width="90">
                  <template #default="{ row }">v{{ row.versionNo }}</template>
                </el-table-column>
                <el-table-column prop="opCode" label="操作码" min-width="160" />
                <el-table-column prop="route" label="路由" min-width="220" show-overflow-tooltip />
                <el-table-column prop="description" label="说明" min-width="200" />
                <el-table-column label="状态" width="90">
                  <template #default="{ row }">
                    {{ row.status === 'ACTIVE' ? '启用' : '禁用' }}
                  </template>
                </el-table-column>
                <el-table-column label="授权" width="80">
                  <template #default="{ row }">{{ row.grantsCount ?? '—' }}</template>
                </el-table-column>
                <el-table-column label="调用" width="80">
                  <template #default="{ row }">{{ row.executionsCount ?? 0 }}</template>
                </el-table-column>
                <el-table-column label="所属本体" min-width="180">
                  <template #default="{ row }">
                    {{ row.spaceName || spaceNames.get(row.spaceId) || row.spaceId }}
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="150">
                  <template #default="{ row }">
                    <el-button
                      link
                      type="primary"
                      @click="router.push(`/delivery?spaceId=${row.spaceId}&tab=delivery`)"
                    >
                      进入发布与服务
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </AsyncState>
          </el-tab-pane>
        </el-tabs>
        <PagerBar
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
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 12px;
}
.global-filter .el-select {
  width: 220px;
}
.global-filter .el-input {
  flex: 1 1 240px;
}
</style>
