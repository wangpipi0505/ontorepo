<script setup lang="ts">
/** 本体空间管理首页：列表即入口，行内进入建模、数据配置、测试与设置。 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { Space } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useSession } from '../stores/session'
import { useResource } from '../composables/useResource'
import { useMutation, confirmAction } from '../composables/useMutation'
import SpaceDialog from '../components/SpaceDialog.vue'
import OverviewMetrics from '../features/overview/OverviewMetrics.vue'
import { useOverviewCards } from '../features/overview/useOverviewCards'
import { releaseLabels } from '../features/overview/overview'
const session = useSession(),
  router = useRouter(),
  api = session.api,
  spaces = useResource<Space[]>([]),
  mutation = useMutation()
const dialog = ref(false),
  selected = ref<Space | null>(null)
const filters = reactive({ name: '', code: '', domain: '', status: '' })
const page = ref(1),
  pageSize = 10
const filtered = computed(() =>
  spaces.data.value.filter(
    (space) =>
      (['name', 'code', 'domain'] as const).every((key) =>
        (space[key] || '').toLocaleLowerCase().includes(filters[key].trim().toLocaleLowerCase()),
      ) &&
      (!filters.status || space.status === filters.status),
  ),
)
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)))
const visible = computed(() =>
  filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize),
)
const overview = useOverviewCards(() => visible.value)
watch(filters, () => {
  page.value = 1
})
function clearFilters() {
  Object.assign(filters, { name: '', code: '', domain: '', status: '' })
}
function overviewState(space: Space) {
  return overview.states.value[overview.key(space)]?.value
}
function releaseText(space: Space) {
  const release = overviewState(space)?.release
  if (release?.value) return `v${release.value.versionNo} · ${releaseLabels[release.value.status]}`
  if (release?.error) return release.error
  return '—'
}
function conflictText(space: Space) {
  const conflicts = overviewState(space)?.conflicts
  if (!conflicts?.value) return conflicts?.error || '—'
  return `${conflicts.value.errors.length} 项错误 · ${conflicts.value.warnings.length} 项警告`
}
function relationRuleText(space: Space) {
  const value = overviewState(space)
  if (!value) return '—'
  return `关系 ${value.relations.value ?? '—'} · 规则 ${value.rules.value ?? '—'}`
}
function mappingText(space: Space) {
  const mapping = overviewState(space)?.mappings
  if (!mapping?.value) return mapping?.error || '—'
  const issues = mapping.value.coverage.pending + mapping.value.coverage.unmappable
  return `${issues} 项问题`
}
function overviewError(space: Space) {
  const value = overviewState(space)
  return (
    value?.release.error ||
    value?.conflicts.error ||
    value?.mappings.error ||
    (overview.states.value[overview.key(space)]?.error ?? '')
  )
}
function enter(space: Space) {
  if (space.status === 'ARCHIVED') return
  void router.push(`/spaces/${space.id}/model`)
}
async function load() {
  overview.reset()
  await spaces.load(() => api.get<Space[]>('/spaces'))
  page.value = Math.min(page.value, pages.value)
  overview.ensure()
}
function edit(space: Space | null) {
  if (!session.has(space ? 'space.write' : 'space.create')) return
  selected.value = space
  dialog.value = true
}
async function archive(space: Space) {
  if (!session.has('space.write')) return
  if (await confirmAction(`归档“${space.name}”后将停止草稿写入与试执行。是否继续？`))
    await mutation.run(async () => {
      await api.post(`/spaces/${space.id}/archive`, { expectedDraftRev: space.draftRev })
      await load()
    })
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>本体建模</h1>
      <p class="muted">先选择一套领域本体，再进入建模工作台；数据配置与测试也可从本列表直接发起。</p>
    </div>
    <el-button v-if="session.has('space.create')" type="primary" @click="edit(null)">
      新建本体空间
    </el-button>
  </div>
  <p v-if="mutation.error.value" class="error-text">{{ mutation.error.value }}</p>
  <section class="panel space-panel">
    <form class="space-filters" @submit.prevent>
      <label>
        空间名称
        <input v-model="filters.name" aria-label="空间名称" placeholder="查询名称" />
      </label>
      <label>
        空间编码
        <input v-model="filters.code" aria-label="空间编码" placeholder="查询编码" />
      </label>
      <label>
        所属领域
        <input v-model="filters.domain" aria-label="所属领域" placeholder="查询领域" />
      </label>
      <label>
        空间状态
        <select v-model="filters.status" aria-label="空间状态">
          <option value="">全部状态</option>
          <option value="ACTIVE">使用中</option>
          <option value="ARCHIVED">已归档</option>
        </select>
      </label>
      <el-button @click="clearFilters">清除筛选</el-button>
      <el-button text @click="load">刷新</el-button>
    </form>
    <AsyncState
      :loading="spaces.loading.value"
      :error="spaces.error.value"
      :empty="!spaces.data.value.length"
      empty-text="还没有可见的本体空间。新建空间，开始建立领域模型。"
      @retry="load"
    >
      <p v-if="!filtered.length" role="status">没有符合筛选条件的本体空间，请调整或清除筛选。</p>
      <el-table
        v-else
        :data="visible"
        row-key="id"
        class="space-table"
        row-class-name="space-card"
        @row-click="enter"
      >
        <el-table-column type="expand" width="36">
          <template #default="{ row }">
            <OverviewMetrics
              :state="overview.states.value[overview.key(row)]"
              @retry="overview.retry(row)"
            />
          </template>
        </el-table-column>
        <el-table-column label="本体名称" min-width="220">
          <template #default="{ row }">
            <router-link class="space-title" :to="`/spaces/${row.id}/model`" @click.stop>
              {{ row.name }}
            </router-link>
            <div class="mono muted row-sub">{{ row.code }}</div>
          </template>
        </el-table-column>
        <el-table-column label="领域" min-width="120">
          <template #default="{ row }">{{ row.domain || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ARCHIVED' ? 'info' : 'success'" size="small">
              {{ row.status === 'ACTIVE' ? '使用中' : '已归档' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="规模" width="150">
          <template #default="{ row }">
            {{ row.classCount }} 对象 · {{ row.propertyCount }} 属性
          </template>
        </el-table-column>
        <el-table-column label="关系 / 规则" width="140">
          <template #default="{ row }">{{ relationRuleText(row) }}</template>
        </el-table-column>
        <el-table-column label="正式版本" width="170">
          <template #default="{ row }">
            <span :class="{ 'error-text-inline': overviewState(row)?.release.error }">
              {{ releaseText(row) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="校验 / 映射" width="170">
          <template #default="{ row }">
            <span>{{ conflictText(row) }} · {{ mappingText(row) }}</span>
            <div v-if="overviewError(row)" class="error-text-inline">{{ overviewError(row) }}</div>
            <el-button
              v-if="overviewError(row)"
              link
              type="primary"
              class="retry-inline"
              @click.stop="overview.retry(row)"
            >
              重试概览
            </el-button>
          </template>
        </el-table-column>
        <el-table-column label="最近修改" width="160">
          <template #default="{ row }">
            {{ row.updatedAt ? new Date(row.updatedAt).toLocaleString('zh-CN') : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <div class="row-actions" @click.stop>
              <el-button v-if="row.status === 'ACTIVE'" link type="primary" @click="enter(row)">
                进入建模
              </el-button>
              <el-button
                link
                type="primary"
                @click="router.push(`/spaces/${row.id}/data?tab=datasources`)"
              >
                数据配置
              </el-button>
              <el-button
                link
                type="primary"
                @click="router.push(`/spaces/${row.id}/delivery?tab=testing`)"
              >
                发起测试
              </el-button>
              <details class="row-more" @click.stop>
                <summary>更多</summary>
                <div class="more-panel">
                  <button type="button" @click="router.push(`/spaces/${row.id}`)">空间概览</button>
                  <button
                    v-if="row.role === 'OWNER'"
                    type="button"
                    @click="router.push(`/spaces/${row.id}/settings`)"
                  >
                    空间设置
                  </button>
                  <button
                    v-if="
                      row.role !== 'VIEWER' &&
                      row.status === 'ACTIVE' &&
                      session.has('space.write')
                    "
                    type="button"
                    @click="edit(row)"
                  >
                    编辑
                  </button>
                  <button
                    v-if="
                      row.role === 'OWNER' &&
                      row.status === 'ACTIVE' &&
                      session.has('space.write')
                    "
                    type="button"
                    class="danger"
                    @click="archive(row)"
                  >
                    归档
                  </button>
                </div>
              </details>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="filtered.length" class="row spread pagination">
        <span class="muted">
          共 {{ filtered.length }} 个本体空间 · 第 {{ page }} / {{ pages }} 页
        </span>
        <div class="row">
          <el-button :disabled="page === 1" @click="page--">上一页</el-button>
          <el-button :disabled="page === pages" @click="page++">下一页</el-button>
        </div>
      </div>
    </AsyncState>
  </section>
  <SpaceDialog v-model="dialog" :space="selected" @saved="load" />
</template>
<style scoped>
.space-panel {
  padding: 12px;
}
.space-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 10px;
  margin-bottom: 12px;
}
.space-filters label {
  display: flex;
  flex: 1 1 150px;
  flex-direction: column;
  gap: 4px;
  color: var(--muted);
  font-size: 12px;
}
.space-filters input,
.space-filters select {
  min-width: 0;
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 7px 9px;
  background: white;
  color: var(--text);
}
.space-table {
  width: 100%;
}
.space-title {
  font-weight: 600;
}
.row-sub {
  font-size: 11px;
  margin-top: 2px;
}
.row-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px;
}
.row-more {
  position: relative;
  display: inline-block;
}
.row-more summary {
  cursor: pointer;
  color: var(--primary);
  font-size: 13px;
  padding: 4px 6px;
}
.more-panel {
  position: absolute;
  right: 0;
  top: 26px;
  z-index: 5;
  min-width: 120px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 6px;
  box-shadow: 0 6px 18px rgb(15 23 42 / 12%);
  padding: 4px;
}
.more-panel button {
  display: block;
  width: 100%;
  border: 0;
  background: transparent;
  text-align: left;
  padding: 6px 8px;
  font-size: 12px;
  color: var(--text);
  cursor: pointer;
}
.more-panel button:hover {
  background: #f1f5ff;
}
.more-panel button.danger {
  color: var(--danger);
}.retry-inline {
  margin-left: 4px;
}
.error-text-inline {
  color: var(--danger);
}
.pagination {
  margin-top: 12px;
  flex-wrap: wrap;
}
</style>
