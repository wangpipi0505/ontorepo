<script setup lang="ts">
import type { Execution, TracedExecution } from '@onto/api-client'
/** 冻结摘要、执行步骤与结果同屏呈现；FAILED 仍是有效执行记录，不冒充请求失败。 */
defineProps<{ execution: Execution | TracedExecution }>()
</script>
<template>
  <div class="execution-result stack">
    <div class="row spread">
      <h3>执行结果</h3>
      <div class="row">
        <el-tag :type="execution.status === 'SUCCEEDED' ? 'success' : 'danger'">
          {{ execution.status === 'SUCCEEDED' ? '执行成功' : '执行失败' }}
        </el-tag>
        <span class="muted">{{ execution.elapsedMs }} ms</span>
      </div>
    </div>
    <p v-if="execution.errorCode" class="error-text" role="alert">
      错误码：{{ execution.errorCode }}
    </p>
    <dl class="details-grid">
      <dt>操作编码</dt>
      <dd class="mono">{{ execution.opCode }}</dd>
      <dt>跟踪标识</dt>
      <dd class="mono">{{ execution.traceId }}</dd>
      <dt>冻结摘要</dt>
      <dd class="mono">{{ execution.candidateSha256 }}</dd>
      <dt>执行时间</dt>
      <dd>{{ new Date(execution.createdAt).toLocaleString('zh-CN') }}</dd>
    </dl>
    <div>
      <h3>执行步骤</h3>
      <ol class="execution-steps">
        <li v-for="(step, index) in execution.steps" :key="`${index}-${step.kind}`">
          <div class="row spread">
            <b>{{ step.kind }}</b>
            <el-tag size="small" :type="step.status === 'FAILED' ? 'danger' : 'info'">
              {{ step.status }}
            </el-tag>
          </div>
          <p>{{ step.detail }}</p>
        </li>
      </ol>
      <p v-if="!execution.steps.length" class="muted">没有步骤记录。</p>
    </div>
    <div>
      <h3>
        结果数据
        <span class="muted">{{ execution.rows.length }} 行</span>
      </h3>
      <div v-if="execution.columns.length" class="overflow">
        <el-table :data="execution.rows" border max-height="400">
          <el-table-column
            v-for="column in execution.columns"
            :key="column"
            :prop="column"
            :label="column"
            min-width="180"
          >
            <template #default="{ row }">
              <span class="mono result-value">{{ row[column] ?? '—' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <p v-else class="muted">本次执行没有返回结果列。</p>
    </div>
  </div>
</template>
<style scoped>
.execution-result h3 {
  margin: 0;
}
.execution-steps {
  padding: 0;
  margin: 14px 0;
  list-style: none;
  display: grid;
  gap: 10px;
  counter-reset: step;
}
.execution-steps li {
  padding: 14px 16px;
  background: var(--canvas);
  border-left: 3px solid #a8bfdf;
  border-radius: 0 4px 4px 0;
  font-size: 12px;
}
.execution-steps p {
  margin: 8px 0 0;
  color: var(--muted);
  overflow-wrap: anywhere;
}
.result-value {
  font-size: 12px;
}
.execution-result .details-grid {
  margin: 0;
}
</style>
