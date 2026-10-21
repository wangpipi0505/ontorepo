<script setup lang="ts">
/** 批量验收入口只接受冻结身份；请求期间串行执行，切换候选或离开空间后丢弃旧响应。 */
import { computed, ref, watch } from 'vue'
import { ApiError, type CandidateTests, type PublicationCandidate } from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import { useMutation } from '../../composables/useMutation'
const props = defineProps<{
  candidate: Pick<PublicationCandidate, 'id' | 'sha256' | 'draftRev'>
  enabled: boolean
  previousResult?: CandidateTests | null
}>()
const emit = defineEmits<{
  busy: [value: boolean]
  completed: [result: CandidateTests]
  conflict: []
}>()
const api = useSession().api,
  workspace = useWorkspace(),
  mutation = useMutation(),
  result = ref<CandidateTests | null>(null)
const displayed = computed(
  () =>
    result.value ||
    (props.previousResult?.candidateSha256 === props.candidate.sha256
      ? props.previousResult
      : null),
)
let generation = 0
const allowed = computed(
  () => props.enabled && workspace.canWriteFor('test.execute') && !mutation.pending.value,
)
watch(mutation.pending, (value) => emit('busy', value), { flush: 'sync' })
watch(
  () => `${props.candidate.id}:${props.candidate.sha256}:${props.candidate.draftRev}`,
  () => {
    generation++
    result.value = null
    mutation.error.value = ''
  },
)
async function execute() {
  if (!allowed.value) return
  const { id, sha256 } = props.candidate,
    ticket = generation
  result.value = null
  const success = await mutation.run(async ({ isCurrent }) => {
    try {
      const response = await api.post<CandidateTests>(`/candidates/${id}/tests`, {})
      if (!isCurrent() || ticket !== generation) return
      if (response.candidateSha256 !== sha256) throw new Error('用例结果摘要与当前候选不符。')
      result.value = response
    } catch (cause) {
      if (isCurrent() && ticket === generation && cause instanceof ApiError && cause.status === 409)
        emit('conflict')
      throw cause
    }
  })
  // 先释放提交锁再通知父级刷新；父级读取时可能卸载此组件，不能让旧 busy 状态留在发布向导。
  if (success && ticket === generation && result.value) emit('completed', result.value)
}
</script>
<template>
  <section class="stack frozen-tests">
    <div class="row spread">
      <div>
        <h3>冻结验收用例 · r{{ candidate.draftRev }}</h3>
        <p class="muted">批量执行该候选内已启用用例；当前草稿的后续修改须重新冻结才会生效。</p>
      </div>
      <el-button :disabled="!allowed" :loading="mutation.pending.value" @click="execute">
        运行冻结验收用例
      </el-button>
    </div>
    <p class="mono frozen-identity">候选 {{ candidate.id }} · SHA-256 {{ candidate.sha256 }}</p>
    <p v-if="mutation.error.value" class="error-text" role="alert">{{ mutation.error.value }}</p>
    <section v-if="displayed" class="stack">
      <h3>冻结验收结果 · {{ displayed.passed && displayed.results.length ? '通过' : '未通过' }}</h3>
      <p v-if="!displayed.results.length" class="error-text">缺少验收用例，发布被阻断。</p>
      <article v-for="test in displayed.results" :key="test.caseId" class="record">
        <b>{{ test.name }} · {{ test.passed ? '通过' : '失败' }}</b>
        <p>{{ test.message }}</p>
        <span class="mono">{{ test.executionId }}</span>
      </article>
    </section>
  </section>
</template>
<style scoped>
.frozen-identity {
  overflow-wrap: anywhere;
  font-size: 12px;
}
.frozen-tests h3 {
  margin-bottom: 8px;
}
</style>
