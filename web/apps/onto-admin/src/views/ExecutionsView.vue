<script setup lang="ts">
/** 候选和正式服务共用持久执行目录；支持按入口/结果/操作筛选并分页。 */
import { onMounted, reactive, ref, shallowRef } from 'vue'
import type { TracedExecution } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useWorkspace } from '../stores/workspace'
import { useSession } from '../stores/session'
import { useResource } from '../composables/useResource'
import ExecutionResult from '../components/ExecutionResult.vue'
const workspace = useWorkspace(),
  api = useSession().api,
  executions = useResource<TracedExecution[]>([]),
  items = shallowRef<TracedExecution[]>([]),
  selected = ref<TracedExecution | null>(null),
  offset = ref(0),
  finished = ref(false),
  lastAppend = ref(false)
const filters = reactive({ caller: '', status: '', opCode: '' })
const callers = ['SERVICE', 'EXTERNAL', 'TRIAL', 'LOCAL_TEST']
const statuses = [
  { value: 'SUCCEEDED', label: '成功' },
  { value: 'FAILED', label: '失败' },
]
function load(append = false) {
  const id = workspace.space?.id
  if (!id || executions.loading.value) return Promise.resolve()
  lastAppend.value = append
  const params = new URLSearchParams({
    limit: '50',
    offset: String(append ? offset.value : 0),
  })
  if (filters.caller) params.set('caller', filters.caller)
  if (filters.status) params.set('status', filters.status)
  if (filters.opCode.trim()) params.set('opCode', filters.opCode.trim())
  return executions
    .load(() => api.get<TracedExecution[]>(`/spaces/${id}/executions?${params}`))
    .then((result) => {
      if (!result) return
      items.value = append ? [...items.value, ...result] : result
      offset.value = items.value.length
      finished.value = result.length < 50
    })
}
function applyFilters() {
  if (executions.loading.value) return
  items.value = []
  offset.value = 0
  finished.value = false
  void load(false)
}
function clearFilters() {
  filters.caller = ''
  filters.status = ''
  filters.opCode = ''
  applyFilters()
}
onMounted(() => load())
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>执行记录</h1>
      <p class="muted">候选与正式服务执行，保留冻结摘要、结果与追溯入口。</p>
    </div>
    <el-button :disabled="executions.loading.value" @click="load(false)">刷新记录</el-button>
  </div>
  <section class="panel stack">
    <div class="row filters">
      <select v-model="filters.caller" aria-label="调用入口">
        <option value="">全部调用入口</option>
        <option v-for="caller in callers" :key="caller" :value="caller">{{ caller }}</option>
      </select>
      <select v-model="filters.status" aria-label="结果">
        <option value="">全部结果</option>
        <option v-for="item in statuses" :key="item.value" :value="item.value">
          {{ item.label }}
        </option>
      </select>
      <el-input v-model="filters.opCode" placeholder="操作编码" clearable />
      <el-button type="primary" :disabled="executions.loading.value" @click="applyFilters">
        查询
      </el-button>
      <el-button :disabled="executions.loading.value" @click="clearFilters">重置</el-button>
    </div>
    <AsyncState
      :loading="executions.loading.value"
      :error="executions.error.value"
      :empty="!items.length"
      empty-text="没有符合条件的执行记录。"
      @retry="load(lastAppend)"
    >
      <div class="overflow">
        <el-table :data="items">
          <el-table-column prop="opCode" label="操作编码" min-width="150" />
          <el-table-column label="对象类型" min-width="120">
            <template #default="{ row }">
              {{ row.subjectType === 'RELEASE' ? '正式版本' : '候选' }}
            </template>
          </el-table-column>
          <el-table-column label="执行对象" min-width="170">
            <template #default="{ row }">
              <span class="mono">{{ row.subjectId ?? '历史记录未保存' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="调用入口" min-width="120">
            <template #default="{ row }">{{ row.caller ?? '历史记录未保存' }}</template>
          </el-table-column>
          <el-table-column label="结果" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 'SUCCEEDED' ? 'success' : 'danger'" size="small">
                {{ row.status === 'SUCCEEDED' ? '成功' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="用时" width="100">
            <template #default="{ row }">{{ row.elapsedMs }} ms</template>
          </el-table-column>
          <el-table-column label="执行时间" min-width="170">
            <template #default="{ row }">
              {{ new Date(row.createdAt).toLocaleString('zh-CN') }}
            </template>
          </el-table-column>
          <el-table-column label="冻结摘要" min-width="160">
            <template #default="{ row }">
              <span class="mono">{{ row.candidateSha256.slice(0, 16) }}…</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="170">
            <template #default="{ row }">
              <el-button link type="primary" @click="selected = row">查看详情</el-button>
              <RouterLink :to="`/spaces/${workspace.space?.id}/executions/${row.id}/trace`">
                执行追溯
              </RouterLink>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div v-if="!finished">
        <el-button :disabled="executions.loading.value" @click="load(true)">加载更多</el-button>
      </div>
    </AsyncState>
  </section>
  <el-dialog :model-value="!!selected" title="执行记录详情" width="900px" @close="selected = null">
    <ExecutionResult v-if="selected" :execution="selected" />
  </el-dialog>
</template>
<style scoped>
.filters {
  flex-wrap: wrap;
  gap: 8px;
}
.filters .el-input,
.filters select {
  width: 180px;
}
</style>
