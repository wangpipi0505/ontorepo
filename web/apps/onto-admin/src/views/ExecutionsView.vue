<script setup lang="ts">
/** 候选和正式服务共用持久执行目录；追溯入口不将历史缺失字段补造成证据。 */
import { onMounted, ref } from 'vue'
import type { TracedExecution } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useWorkspace } from '../stores/workspace'
import { useSession } from '../stores/session'
import { useResource } from '../composables/useResource'
import ExecutionResult from '../components/ExecutionResult.vue'
const workspace = useWorkspace(),
  api = useSession().api,
  executions = useResource<TracedExecution[]>([]),
  selected = ref<TracedExecution | null>(null)
function load() {
  const id = workspace.space?.id
  return id
    ? executions.load(() => api.get<TracedExecution[]>(`/spaces/${id}/executions`))
    : Promise.resolve()
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>执行记录</h1>
      <p class="muted">最近 50 次候选与正式服务执行，保留冻结摘要、结果与追溯入口。</p>
    </div>
    <el-button @click="load">刷新记录</el-button>
  </div>
  <section class="panel">
    <AsyncState
      :loading="executions.loading.value"
      :error="executions.error.value"
      :empty="!executions.data.value.length"
      empty-text="暂无执行记录。可前往“候选与试执行”验证冻结操作。"
      @retry="load"
    >
      <div class="overflow">
        <el-table :data="executions.data.value">
          <el-table-column prop="opCode" label="操作编码" min-width="150" />
          <el-table-column prop="subjectType" label="对象类型" min-width="120" />
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
    </AsyncState>
  </section>
  <el-dialog :model-value="!!selected" title="执行记录详情" width="900px" @close="selected = null">
    <ExecutionResult v-if="selected" :execution="selected" />
  </el-dialog>
</template>
