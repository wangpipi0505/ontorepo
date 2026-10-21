<script setup lang="ts">
/** 审计按对象/动作/时间检索；游标只追加，不覆盖已读事实。 */
import { inject, reactive, ref, shallowRef, watch } from 'vue'
import { routeLocationKey } from 'vue-router'
import type { AuditPage } from '../../../../../packages/api-client/src/release'
import { useWorkspace } from '../../stores/workspace'
import { useSession } from '../../stores/session'
import { useResource } from '../../composables/useResource'
import {
  auditActionLabel,
  auditResultLabels,
  auditSubjectLabels,
} from '../../composables/auditLabels'
import './p4.css'
const route = inject(routeLocationKey, undefined),
  workspace = useWorkspace(),
  api = useSession().api,
  request = useResource<AuditPage | null>(null)
const items = shallowRef<AuditPage['items']>([]),
  cursor = ref<number | null>(null),
  initialized = ref(false),
  lastAppend = ref(false)
const filters = reactive({
  action: '',
  subjectType: typeof route?.query.subjectType === 'string' ? route.query.subjectType : '',
  subjectId: typeof route?.query.subjectId === 'string' ? route.query.subjectId : '',
  from: '',
  to: '',
})
const subjectTypes = ['CANDIDATE', 'RELEASE', 'DATASOURCE', 'MAPPING', 'OPERATION', 'SPACE']

function load(append = false) {
  const id = workspace.space?.id
  if (!id || request.loading.value || (append && cursor.value === null)) return
  lastAppend.value = append
  const params = new URLSearchParams({ spaceId: id, limit: '50' })
  if (append && cursor.value !== null) params.set('beforeId', String(cursor.value))
  if (filters.action.trim()) params.set('action', filters.action.trim())
  if (filters.subjectType) params.set('subjectType', filters.subjectType)
  if (filters.subjectId.trim()) params.set('subjectId', filters.subjectId.trim())
  if (filters.from.trim()) params.set('from', filters.from.trim())
  if (filters.to.trim()) params.set('to', filters.to.trim())
  return request.load(() =>
    api.get<AuditPage>(`/audit?${params.toString()}`),
  ).then((result) => {
    if (!result) return
    const known = new Set(append ? items.value.map((item) => item.id) : [])
    items.value = [
      ...(append ? items.value : []),
      ...result.items.filter((item) => !known.has(item.id)),
    ]
    cursor.value = result.nextBeforeId
    initialized.value = true
  })
}
function applyFilters() {
  if (request.loading.value) return
  items.value = []
  cursor.value = null
  initialized.value = false
  void load(false)
}
function clearFilters() {
  filters.action = ''
  filters.subjectType = ''
  filters.subjectId = ''
  filters.from = ''
  filters.to = ''
  applyFilters()
}
watch(
  () => workspace.space?.id,
  () => {
    items.value = []
    cursor.value = null
    initialized.value = false
    request.reset()
    void load(false)
  },
  { immediate: true },
)
</script>
<template>
  <div class="p4-page stack">
    <div class="page-heading">
      <div>
        <h1>审计记录</h1>
        <p class="muted">按对象、动作与时间查看模型、发布、授权与数据处理活动。</p>
      </div>
      <el-button :disabled="request.loading.value" @click="load()">刷新审计</el-button>
    </div>
    <section class="panel stack">
      <div class="row filters">
        <el-input v-model="filters.action" placeholder="动作编码，如 RELEASE_COMMIT" clearable />
        <select v-model="filters.subjectType" aria-label="对象类型">
          <option value="">全部对象类型</option>
          <option v-for="type in subjectTypes" :key="type" :value="type">
              {{ auditSubjectLabels[type] ?? type }}
            </option>
        </select>
        <el-input v-model="filters.subjectId" placeholder="对象 ID" clearable />
        <el-input v-model="filters.from" placeholder="开始（ISO，如 2026-10-21T00:00:00Z）" />
        <el-input v-model="filters.to" placeholder="结束（ISO，如 2026-10-21T23:59:59Z）" />
        <el-button type="primary" :disabled="request.loading.value" @click="applyFilters">
          查询
        </el-button>
        <el-button :disabled="request.loading.value" @click="clearFilters">重置</el-button>
      </div>
      <p v-if="request.error.value" class="error-text" role="alert">
        {{ request.error.value }}
        <el-button :disabled="request.loading.value" @click="load(lastAppend)">重试读取</el-button>
      </p>
      <p v-if="request.loading.value" role="status" class="muted">正在读取审计记录…</p>
      <p v-if="initialized && !items.length" class="muted">没有符合条件的审计活动。</p>
      <article v-for="item in items" :key="item.id" class="record">
        <div class="row spread">
          <b>#{{ item.id }} · {{ auditActionLabel(item.action) }}</b>
          <time>{{ new Date(item.createdAt).toLocaleString('zh-CN') }}</time>
        </div>
        <p class="muted">
          操作人 {{ item.actorName || item.actorId || '未记录' }} · 对象
          {{ item.subjectType ? (auditSubjectLabels[item.subjectType] ?? item.subjectType) : '—' }}
          {{ item.subjectId || '未记录' }} · 结果 {{ item.result ? (auditResultLabels[item.result] ?? item.result) : '—' }}
        </p>
        <details>
          <summary>安全审计详情</summary>
          <pre>{{ JSON.stringify(item.detail, null, 2) }}</pre>
        </details>
      </article>
      <div v-if="cursor !== null">
        <el-button :disabled="request.loading.value" @click="load(true)">加载更早记录</el-button>
      </div>
    </section>
  </div>
</template>
<style scoped>
.filters {
  flex-wrap: wrap;
  gap: 8px;
}
.filters .el-input,
.filters select {
  width: 200px;
}
</style>
