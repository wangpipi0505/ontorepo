<script setup lang="ts">
/** 测试与服务封装全局视图：跨可见本体汇总候选与正式版本。 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { Space } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useSession } from '../stores/session'
import { useResource } from '../composables/useResource'
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
interface GlobalDelivery {
  candidates: CandidateRow[]
  releases: ReleaseRow[]
}
const router = useRouter(),
  api = useSession().api,
  data = useResource<GlobalDelivery | null>(null),
  spaces = useResource<Space[]>([])
const spaceFilter = ref(''),
  keyword = ref(''),
  tab = ref<'candidates' | 'releases'>('candidates')
const spaceNames = computed(() => new Map(spaces.data.value.map((item) => [item.id, item.name])))
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
async function load() {
  await Promise.all([
    data.load(() => api.get<GlobalDelivery>('/global/delivery')),
    spaces.load(() => api.get<Space[]>('/spaces')),
  ])
}
onMounted(load)
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
            <el-table :data="candidates" row-key="id">
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
              <el-table-column prop="status" label="状态" width="140" />
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
            <el-table :data="releases" row-key="id">
              <el-table-column label="版本" width="100">
                <template #default="{ row }">v{{ row.versionNo }}</template>
              </el-table-column>
              <el-table-column label="所属本体" min-width="180">
                <template #default="{ row }">
                  {{ row.spaceName || spaceNames.get(row.spaceId) || row.spaceId }}
                </template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="140" />
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
  flex: 1 1 240px;
}
</style>
