<script setup lang="ts">
/** 读取持久 trace，不从当前模型推导历史证据；缺失历史事实明确展示为空。 */
import { ref, watch } from 'vue'
import type { ExecutionTrace } from '../../../../../packages/api-client/src/release'
import { useSession } from '../../stores/session'
import { useResource } from '../../composables/useResource'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import TraceGraph from './TraceGraph.vue'
import './p4.css'
const props = defineProps<{ executionId: string }>(),
  api = useSession().api
const state = useResource<ExecutionTrace | null>(null),
  selectedGraphNode = ref('')
function load() {
  const id = props.executionId
  if (!id) return
  return state.load(async () => {
    const value = await api.get<ExecutionTrace>(`/executions/${id}/trace`)
    if (value.execution.id !== id) throw new Error('追溯响应执行标识不符，请重新读取。')
    return value
  })
}
watch(
  () => props.executionId,
  () => {
    state.reset()
    void load()
  },
  { immediate: true },
)
</script>
<template>
  <section class="p4-page panel stack">
    <div class="row spread">
      <h2>执行追溯</h2>
      <el-button :disabled="state.loading.value" @click="load">刷新追溯</el-button>
    </div>
    <AsyncState :loading="state.loading.value" :error="state.error.value" @retry="load">
      <template v-if="state.data.value">
        <dl class="details-grid">
          <dt>执行标识</dt>
          <dd>{{ state.data.value.execution.id }}</dd>
          <dt>执行对象类型</dt>
          <dd>{{ state.data.value.execution.subjectType }}</dd>
          <dt>执行对象</dt>
          <dd>{{ state.data.value.execution.subjectId ?? '未记录' }}</dd>
          <dt>调用入口</dt>
          <dd>{{ state.data.value.execution.caller ?? '历史记录未保存' }}</dd>
          <dt>结果</dt>
          <dd>
            {{ state.data.value.execution.status }} ·
            {{ state.data.value.execution.errorCode ?? '无错误码' }}
          </dd>
          <dt>冻结摘要</dt>
          <dd class="mono">{{ state.data.value.execution.candidateSha256 }}</dd>
        </dl>
        <TraceGraph :graph="state.data.value.graph" @select-node="selectedGraphNode = $event" />
        <section class="stack">
          <h3>授权检查</h3>
          <p v-if="!state.data.value.authorization.length" class="muted">
            此历史记录未保存授权步骤。
          </p>
          <article v-for="item in state.data.value.authorization" :key="item.order" class="record">
            <b>
              {{ item.order }} · {{ item.kind }} ·
              {{ item.passed === null ? '未记录' : item.passed ? '通过' : '拒绝' }}
            </b>
            <pre>{{
              typeof item.detail === 'string' ? item.detail : JSON.stringify(item.detail, null, 2)
            }}</pre>
          </article>
        </section>
        <section class="stack">
          <h3>事实时间线</h3>
          <p v-if="!state.data.value.timeline.length" class="muted">此记录没有时间线事实。</p>
          <ol class="timeline">
            <li
              v-for="item in state.data.value.timeline"
              :key="item.id"
              :class="{ 'timeline-active': item.id === selectedGraphNode }"
            >
              <time>{{ item.at ?? '时间未记录' }}</time>
              <b>{{ item.title }} · {{ item.kind }}</b>
              <details>
                <summary>详情</summary>
                <pre>{{
                  typeof item.detail === 'string'
                    ? item.detail
                    : JSON.stringify(item.detail, null, 2)
                }}</pre>
              </details>
            </li>
          </ol>
        </section>
        <section class="stack">
          <h3>执行步骤</h3>
          <article v-for="(step, index) in state.data.value.steps" :key="index" class="record">
            <b>{{ step.kind }} · {{ step.status }}</b>
            <p>{{ step.detail }}</p>
          </article>
        </section>
        <section class="stack">
          <h3>证据目录</h3>
          <p v-if="!state.data.value.evidence.length" class="muted">此记录未保存证据条目。</p>
          <details v-for="evidence in state.data.value.evidence" :key="evidence.id" class="record">
            <summary>{{ evidence.type }} · {{ evidence.ref ?? '引用未记录' }}</summary>
            <p class="identity">SHA-256 {{ evidence.sha256 ?? '未记录' }}</p>
            <pre>{{ JSON.stringify(evidence.detail, null, 2) }}</pre>
          </details>
        </section>
        <details>
          <summary>持久业务结果</summary>
          <pre>{{ JSON.stringify(state.data.value.execution.rows, null, 2) }}</pre>
        </details>
      </template>
    </AsyncState>
  </section>
</template>
<style scoped>
.timeline {
  margin: 0;
  padding-left: 22px;
}
.timeline li {
  padding: 0 0 18px 12px;
  border-left: 2px solid var(--line);
  overflow-wrap: anywhere;
}
.timeline li.timeline-active {
  background: #eff6ff;
  border-left-color: #2563eb;
}
.timeline time {
  display: block;
  color: var(--muted);
  font-size: 12px;
  margin-bottom: 8px;
}
</style>
