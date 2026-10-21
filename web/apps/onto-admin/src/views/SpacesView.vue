<script setup lang="ts">
/** 空间管理入口，只展示服务端授权可见的数据；删除性归档须确认，列表失败允许手动重试。 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import type { Space } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useSession } from '../stores/session'
import { useResource } from '../composables/useResource'
import { useMutation, confirmAction } from '../composables/useMutation'
import { roleLabels } from '../composables/labels'
import SpaceDialog from '../components/SpaceDialog.vue'
import OverviewMetrics from '../features/overview/OverviewMetrics.vue'
import { useOverviewCards } from '../features/overview/useOverviewCards'
const session = useSession(),
  api = session.api,
  spaces = useResource<Space[]>([]),
  mutation = useMutation()
const dialog = ref(false),
  selected = ref<Space | null>(null)
const filters = reactive({ name: '', code: '', domain: '', status: '' })
const page = ref(1),
  pageSize = 6
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
      <h1>工作空间</h1>
      <p class="muted">从一个领域开始，组织对象、属性与可执行的模型查询。</p>
    </div>
    <el-button v-if="session.has('space.create')" type="primary" @click="edit(null)">
      新建空间
    </el-button>
  </div>
  <p v-if="mutation.error.value" class="error-text">{{ mutation.error.value }}</p>
  <section class="panel">
    <div class="row spread list-heading">
      <h2>我的空间</h2>
      <el-button text @click="load">刷新</el-button>
    </div>
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
    </form>
    <AsyncState
      :loading="spaces.loading.value"
      :error="spaces.error.value"
      :empty="!spaces.data.value.length"
      empty-text="还没有可见空间。新建空间，开始建立领域模型。"
      @retry="load"
    >
      <p v-if="!filtered.length" role="status">没有符合筛选条件的空间，请调整或清除筛选。</p>
      <div class="space-grid">
        <article v-for="space in visible" :key="space.id" class="space-card">
          <div class="row spread">
            <span class="mono muted">{{ space.code }}</span>
            <el-tag :type="space.status === 'ARCHIVED' ? 'info' : 'success'" size="small">
              {{ space.status === 'ACTIVE' ? '使用中' : '已归档' }}
            </el-tag>
          </div>
          <router-link :to="`/spaces/${space.id}`" class="space-title">
            {{ space.name }}
          </router-link>
          <p class="muted domain">{{ space.description || space.domain || '暂无说明' }}</p>
          <p v-if="space.updatedAt" class="muted card-updated">
            最近修改 {{ new Date(space.updatedAt).toLocaleString('zh-CN') }}
          </p>
          <div class="space-counts">
            <span>
              <b>{{ space.classCount }}</b>
              对象
            </span>
            <span>
              <b>{{ space.propertyCount }}</b>
              属性
            </span>
            <span>
              <b>{{ space.operationCount }}</b>
              操作
            </span>
          </div>
          <OverviewMetrics
            :state="overview.states.value[overview.key(space)]"
            @retry="overview.retry(space)"
          />
          <div class="row spread card-foot">
            <span class="muted">{{ roleLabels[space.role] }} · r{{ space.draftRev }}</span>
            <div class="row">
              <el-button
                v-if="
                  space.role !== 'VIEWER' && space.status === 'ACTIVE' && session.has('space.write')
                "
                link
                type="primary"
                @click="edit(space)"
              >
                编辑
              </el-button>
              <el-button
                v-if="
                  space.role === 'OWNER' && space.status === 'ACTIVE' && session.has('space.write')
                "
                link
                type="danger"
                :disabled="mutation.pending.value"
                @click="archive(space)"
              >
                归档
              </el-button>
              <router-link :to="`/spaces/${space.id}`">进入空间 →</router-link>
            </div>
          </div>
        </article>
      </div>
      <div v-if="filtered.length" class="row spread pagination">
        <span class="muted">共 {{ filtered.length }} 个空间 · 第 {{ page }} / {{ pages }} 页</span>
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
.space-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 12px;
  margin-bottom: 22px;
}
.space-filters label {
  display: flex;
  flex: 1 1 150px;
  flex-direction: column;
  gap: 6px;
  color: var(--muted);
  font-size: 12px;
}
.space-filters input,
.space-filters select {
  min-width: 0;
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 9px;
  background: white;
  color: var(--text);
}
.pagination {
  margin-top: 20px;
  flex-wrap: wrap;
}
.list-heading {
  margin-bottom: 20px;
}
.list-heading h2 {
  margin: 0;
}
.space-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}
.space-card {
  border: 1px solid var(--line);
  padding: 22px;
  border-radius: 6px;
  min-width: 0;
}
.space-title {
  display: block;
  font-size: 19px;
  font-weight: 600;
  color: var(--text);
  margin: 20px 0 8px;
  overflow-wrap: anywhere;
}
.space-title:hover {
  color: var(--primary);
}
.domain {
  min-height: 44px;
  overflow-wrap: anywhere;
}
.space-counts {
  display: flex;
  gap: 22px;
  font-size: 12px;
  color: var(--muted);
  margin: 22px 0;
}
.space-counts b {
  font-size: 21px;
  color: var(--text);
  margin-right: 3px;
}
.card-foot {
  border-top: 1px solid var(--line);
  padding-top: 16px;
  font-size: 12px;
}
@media (max-width: 600px) {
  .space-grid {
    grid-template-columns: 1fr;
  }
  .space-card {
    padding: 16px;
  }
}
</style>
