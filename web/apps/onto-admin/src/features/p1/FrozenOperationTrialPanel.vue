<script setup lang="ts">
/** 规则页和候选页共享的冻结试执行器；规则标识仅用于筛选，绝不随执行请求覆盖冻结规则。 */
import { computed, ref, watch } from 'vue'
import type { Execution, Operation, PublicationCandidate } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import { useResource } from '../../composables/useResource'
import { useMutation } from '../../composables/useMutation'
import ExecutionResult from '../../components/ExecutionResult.vue'
import { parseJson } from './inputs'
const props = defineProps<{
  candidate: Pick<PublicationCandidate, 'id' | 'sha256' | 'draftRev'>
  ruleId?: string
}>()
const emit = defineEmits<{ busy: [value: boolean] }>()
const api = useSession().api,
  workspace = useWorkspace(),
  operations = useResource<Operation[]>([]),
  trial = useMutation(),
  operationId = ref(''),
  inputText = ref('{}'),
  execution = ref<Execution | null>(null)
let generation = 0
const choices = computed(() =>
  operations.data.value.filter(
    (operation) =>
      !props.ruleId ||
      (['RULE_CHECK', 'SCORING'].includes(operation.kind) &&
        operation.ruleIds?.includes(props.ruleId)),
  ),
)
function load() {
  const id = props.candidate.id
  return operations.load(() => api.get<Operation[]>(`/candidates/${id}/operations`))
}
watch(
  () =>
    `${props.candidate.id}:${props.candidate.sha256}:${props.candidate.draftRev}:${props.ruleId || ''}`,
  () => {
    generation++
    operationId.value = ''
    execution.value = null
    inputText.value = '{}'
    operations.reset()
    trial.error.value = ''
    void load()
  },
  { immediate: true },
)
watch(trial.pending, (value) => emit('busy', value), { flush: 'sync' })
async function execute() {
  const id = props.candidate.id,
    opId = operationId.value,
    ticket = generation
  if (
    !workspace.canWriteFor('candidate.execute') ||
    !choices.value.some((operation) => operation.id === opId)
  )
    return
  execution.value = null
  await trial.run(async ({ isCurrent }) => {
    const response = await api.post<Execution>(`/candidates/${id}/trial`, {
      operationId: opId,
      input: parseJson(inputText.value, '试执行输入'),
    })
    if (isCurrent() && ticket === generation) execution.value = response
  })
}
</script>
<template>
  <section class="stack">
    <div>
      <h2>试执行 · 草稿 r{{ candidate.draftRev }}</h2>
      <p class="muted">操作、规则和模型均从此候选的冻结内容读取，结果保留完整执行记录。</p>
      <dl class="details-grid">
        <dt>候选标识</dt>
        <dd class="mono">{{ candidate.id }}</dd>
        <dt>SHA-256</dt>
        <dd class="mono">{{ candidate.sha256 }}</dd>
      </dl>
    </div>
    <AsyncState
      :loading="operations.loading.value"
      :error="operations.error.value"
      :empty="!choices.length"
      :empty-text="
        ruleId
          ? '此候选没有绑定当前规则的冻结操作。请先绑定规则并冻结新候选。'
          : '此候选没有冻结操作。请在草稿中添加操作并重新冻结。'
      "
      @retry="load"
    >
      <el-form label-position="top" @submit.prevent="execute">
        <el-form-item label="选择冻结操作">
          <el-select
            v-model="operationId"
            placeholder="请选择此候选内的操作"
            :disabled="trial.pending.value"
            style="width: 100%"
          >
            <el-option
              v-for="operation in choices"
              :key="operation.id"
              :value="operation.id"
              :label="`${operation.name}（${operation.code}）`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="试执行输入（JSON）">
          <el-input
            v-model="inputText"
            type="textarea"
            :rows="5"
            :disabled="trial.pending.value || !workspace.canWriteFor('candidate.execute')"
          />
        </el-form-item>
        <el-button
          v-if="workspace.canWriteFor('candidate.execute')"
          type="primary"
          :disabled="!operationId"
          :loading="trial.pending.value"
          @click="execute"
        >
          试执行
        </el-button>
        <p v-else class="muted">当前空间或账号仅可查看，无法试执行。</p>
      </el-form>
    </AsyncState>
    <p v-if="trial.error.value" class="error-text" role="alert">
      {{ trial.error.value }} 可以重新选择操作后重试。
    </p>
    <ExecutionResult v-if="execution" :execution="execution" />
  </section>
</template>
