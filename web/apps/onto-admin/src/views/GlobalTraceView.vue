<script setup lang="ts">
/** 数据溯源审计全局视图：跨可见本体汇总执行与审计，支持按本体检索。 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { Execution, Space } from '@onto/api-client'
import type { AuditPage } from '../../../../packages/api-client/src/release'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useSession } from '../stores/session'
import { useResource } from '../composables/useResource'
import {
  auditActionLabel,
  auditResultLabels,
  auditSubjectLabels,
  executionCallerLabels,
  executionStatusLabels,
} from '../composables/auditLabels'
type ExecutionRow = Execution & { spaceName?: string }
type AuditRow = AuditPage['items'][number] & { spaceName?: string }
interface GlobalTrace {
  executions: ExecutionRow[]
  audit: AuditRow[]
}
const router = useRouter(),
  api = useSession().api,
  data = useResource<GlobalTrace | null>(null),
  spaces = useResource<Space[]>([])
const spaceFilter = ref(''),
  domainFilter = ref(''),
  statusFilter = ref(''),
  actionFilter = ref(''),
  keyword = ref(''),
  execPage = ref(1),
  auditPage = ref(1),
  pageSize = 30,
  tab = ref<'executions' | 'audit'>('executions')
const spaceNames = computed(() => new Map(spaces.data.value.map((item) => [item.id, item.name])))
const domains = computed(
  () => [...new Set(spaces.data.value.map((item) => item.domain).filter(Boolean))] as string[],
)
const matchingSpaces = computed(() => {
  const text = keyword.value.trim().toLocaleLowerCase()
  return spaces.data.value.filter(
    (space) =>
      (!spaceFilter.value || space.id === spaceFilter.value) &&
      (!domainFilter.value || space.domain === domainFilter.value) &&
      (!statusFilter.value || space.status === statusFilter.value) &&
      (!text ||
        `${space.name} ${space.code} ${space.domain || ''}`.toLocaleLowerCase().includes(text)),
  )
})
const matchingIds = computed(() => new Set(matchingSpaces.value.map((space) => space.id)))
function filterRows<T extends { spaceId?: string; spaceName?: string }>(rows: T[]) {
  return rows.filter((row) => matchingIds.value.has(row.spaceId ?? ''))
}
const executions = computed(() => filterRows(data.data.value?.executions ?? []))
const audit = computed(() =>
  filterRows(data.data.value?.audit ?? []).filter(
    (row) => !actionFilter.value || row.action === actionFilter.value,
  ),
)
const pagedExecutions = computed(() =>
  executions.value.slice((execPage.value - 1) * pageSize, execPage.value * pageSize),
)
const pagedAudit = computed(() =>
  audit.value.slice((auditPage.value - 1) * pageSize, auditPage.value * pageSize),
)
const execPages = computed(() => Math.max(1, Math.ceil(executions.value.length / pageSize)))
const auditPages = computed(() => Math.max(1, Math.ceil(audit.value.length / pageSize)))
const actionStats = computed(() => {
  const counts = new Map<string, number>()
  for (const row of filterRows(data.data.value?.audit ?? []))
    counts.set(row.action, (counts.get(row.action) ?? 0) + 1)
  const max = Math.max(1, ...counts.values())
  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 8)
    .map(([action, count]) => ({ action, label: auditActionLabel(action), count, max }))
})
watch([spaceFilter, domainFilter, statusFilter, keyword], () => {
  execPage.value = 1
  auditPage.value = 1
})
watch(actionFilter, () => {
  auditPage.value = 1
})
async function load() {
  await Promise.all([
    data.load(() => api.get<GlobalTrace>('/global/trace')),
    spaces.load(() => api.get<Space[]>('/spaces')),
  ])
}
onMounted(load)
</script>
<template>
  <section class="module-page">
    <header class="module-heading">
      <div>
        <h1>数据溯源审计</h1>
        <p class="muted">跨本体按名称检索执行与审计；进入所属本体查看逐执行关系图与完整链路。</p>
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
        <el-select v-model="domainFilter" placeholder="全部领域" clearable>
          <el-option label="全部领域" value="" />
          <el-option v-for="domain in domains" :key="domain" :label="domain" :value="domain" />
        </el-select>
        <el-select v-model="statusFilter" placeholder="全部状态" clearable>
          <el-option label="全部状态" value="" />
          <el-option label="使用中" value="ACTIVE" />
          <el-option label="已归档" value="ARCHIVED" />
        </el-select>
        <el-input v-model="keyword" placeholder="本体名称 / 编码 / 领域" clearable />
        <el-button @click="load">刷新</el-button>
      </div>
      <AsyncState :loading="data.loading.value" :error="data.error.value" @retry="load">
        <el-tabs v-model="tab">
          <el-tab-pane :label="`执行 ${executions.length}`" name="executions">
            <el-table :data="pagedExecutions" row-key="id">
              <el-table-column label="执行" width="170">
                <template #default="{ row }">
                  <span class="mono">{{ String(row.id).slice(0, 8) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="所属本体" min-width="180">
                <template #default="{ row }">
                  {{ row.spaceName || spaceNames.get(row.spaceId) || row.spaceId }}
                </template>
              </el-table-column>
              <el-table-column prop="opCode" label="操作" min-width="140" />
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  {{ executionStatusLabels[row.status] ?? row.status }}
                </template>
              </el-table-column>
              <el-table-column label="调用方" width="150">
                <template #default="{ row }">
                  {{ executionCallerLabels[row.caller] ?? row.caller }}
                </template>
              </el-table-column>
              <el-table-column label="操作" width="140">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    @click="
                      router.push(`/spaces/${row.spaceId}/executions/${row.id}/trace`)
                    "
                  >
                    查看追溯
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="pager">
              <span>共 {{ executions.length }} 条 · 第 {{ execPage }} / {{ execPages }} 页</span>
              <div class="row">
                <el-button :disabled="execPage === 1" @click="execPage--">上一页</el-button>
                <el-button :disabled="execPage >= execPages" @click="execPage++">下一页</el-button>
              </div>
            </div>
          </el-tab-pane>
          <el-tab-pane :label="`审计 ${audit.length}`" name="audit">
            <div class="audit-viz">
              <div class="row spread">
                <b>动作分布</b>
                <el-button v-if="actionFilter" link @click="actionFilter = ''">清除筛选</el-button>
              </div>
              <button
                v-for="item in actionStats"
                :key="item.action"
                type="button"
                :class="{ active: actionFilter === item.action }"
                @click="actionFilter = actionFilter === item.action ? '' : item.action"
              >
                <span class="viz-label">{{ item.label }}</span>
                <span class="viz-bar">
                  <i :style="{ width: `${Math.round((item.count / item.max) * 100)}%` }" />
                </span>
                <b>{{ item.count }}</b>
              </button>
            </div>
            <el-table :data="pagedAudit" row-key="id">
              <el-table-column label="动作" min-width="160">
                <template #default="{ row }">{{ auditActionLabel(row.action) }}</template>
              </el-table-column>
              <el-table-column label="所属本体" min-width="180">
                <template #default="{ row }">
                  {{ row.spaceName || spaceNames.get(row.spaceId) || row.spaceId }}
                </template>
              </el-table-column>
              <el-table-column prop="actorName" label="操作人" width="130" />
              <el-table-column label="对象类型" width="110">
                <template #default="{ row }">
                  {{ auditSubjectLabels[row.subjectType ?? ''] ?? row.subjectType ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column label="结果" width="90">
                <template #default="{ row }">
                  {{ auditResultLabels[row.result ?? ''] ?? row.result ?? '—' }}
                </template>
              </el-table-column>
              <el-table-column prop="createdAt" label="时间" width="190" />
              <el-table-column label="操作" width="120">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    @click="router.push(`/trace?spaceId=${row.spaceId}&tab=audit`)"
                  >
                    进入审计
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="pager">
              <span>共 {{ audit.length }} 条 · 第 {{ auditPage }} / {{ auditPages }} 页</span>
              <div class="row">
                <el-button :disabled="auditPage === 1" @click="auditPage--">上一页</el-button>
                <el-button :disabled="auditPage >= auditPages" @click="auditPage++">下一页</el-button>
              </div>
            </div>
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
.pager {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 2px 2px;
  color: var(--muted);
  font-size: 12px;
}
.audit-viz {
  display: grid;
  gap: 6px;
  padding: 10px 12px;
  margin-bottom: 10px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #f8fafc;
}
.audit-viz button {
  display: grid;
  grid-template-columns: 150px minmax(0, 1fr) 48px;
  gap: 10px;
  align-items: center;
  border: 0;
  background: transparent;
  padding: 3px 4px;
  text-align: left;
  cursor: pointer;
  border-radius: 4px;
}
.audit-viz button:hover,
.audit-viz button.active {
  background: #eaf1ff;
}
.viz-label {
  font-size: 12px;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.viz-bar {
  height: 8px;
  background: #e2e8f0;
  border-radius: 999px;
  overflow: hidden;
}
.viz-bar i {
  display: block;
  height: 100%;
  background: var(--primary);
}
.audit-viz b {
  font-size: 12px;
  text-align: right;
}
</style>
