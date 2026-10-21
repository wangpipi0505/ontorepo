<script setup lang="ts">
/** 独立内部调用入口：body 只有业务输入，调用身份和可信属性始终由服务器取得。 */
import { ref, watch } from 'vue'
import { ApiError, type Candidate, type Operation, type Envelope } from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import { useResource } from '../../composables/useResource'
import { useMutation } from '../../composables/useMutation'
import { jsonField, FieldError } from './fields'
import { verifyInvocationMeta } from './candidateIdentity'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
const props = defineProps<{ candidate: Candidate }>(),
  api = useSession().api,
  workspace = useWorkspace()
const operations = useResource<Operation[]>([]),
  mutation = useMutation()
const code = ref(''),
  input = ref('{}'),
  inputError = ref(''),
  result = ref<Envelope<unknown> | null>(null)
let sequence = 0
function loadOperations() {
  const id = props.candidate.id
  return operations.load(() => api.get<Operation[]>(`/candidates/${id}/operations`))
}
watch(
  () => `${props.candidate.id}:${props.candidate.sha256}`,
  () => {
    sequence++
    operations.reset()
    code.value = ''
    input.value = '{}'
    result.value = null
    mutation.error.value = ''
    inputError.value = ''
    void loadOperations()
  },
  { immediate: true },
)
async function execute() {
  if (
    !workspace.canWriteFor('service.execute') ||
    !operations.data.value.some((operation) => operation.code === code.value)
  )
    return
  const { id, sha256 } = props.candidate,
    ticket = sequence,
    opCode = code.value
  result.value = null
  inputError.value = ''
  await mutation.run(async () => {
    try {
      const value = jsonField(input.value, 'input', '操作输入')
      const response = await api.requestEnvelope<unknown>(
        `/internal/candidates/${id}/ops/${encodeURIComponent(opCode)}`,
        'POST',
        value,
      )
      verifyInvocationMeta(response.meta, sha256)
      if (ticket === sequence) result.value = response
    } catch (cause) {
      if (ticket !== sequence) return
      if (cause instanceof FieldError) inputError.value = cause.message
      if (cause instanceof ApiError) throw new Error(`${cause.code}：${cause.message}`)
      throw cause
    }
  })
}
</script>
<template>
  <section class="panel stack">
    <div>
      <h2>内部候选调用</h2>
      <p class="muted">
        使用当前账号调用冻结操作。旧格式候选、缺少绑定或权限不足由服务端拒绝，错误保留用于修复。
      </p>
    </div>
    <AsyncState
      :loading="operations.loading.value"
      :error="operations.error.value"
      @retry="loadOperations"
    >
      <el-form
        label-position="top"
        :disabled="mutation.pending.value || !workspace.canWriteFor('service.execute')"
        @submit.prevent="execute"
      >
        <el-form-item label="冻结操作">
          <el-select v-model="code" placeholder="请选择此候选中的操作">
            <el-option
              v-for="operation in operations.data.value"
              :key="operation.id"
              :value="operation.code"
              :label="`${operation.name}（${operation.code}）`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="操作输入（JSON）" :error="inputError">
          <el-input v-model="input" type="textarea" :rows="5" />
        </el-form-item>
        <el-button
          v-if="workspace.canWriteFor('service.execute')"
          native-type="submit"
          type="primary"
          :disabled="!code"
          :loading="mutation.pending.value"
        >
          调用冻结操作
        </el-button>
        <p v-else class="muted">当前账号或空间仅可查看，内部调用需要编辑权限。</p>
      </el-form>
    </AsyncState>
    <p v-if="mutation.error.value" class="error-text" role="alert">{{ mutation.error.value }}</p>
    <template v-if="result">
      <h3>业务结果</h3>
      <pre class="result-json">{{ JSON.stringify(result.data, null, 2) }}</pre>
      <h3>候选元数据 · preview</h3>
      <pre class="result-json">{{ JSON.stringify(result.meta, null, 2) }}</pre>
    </template>
  </section>
</template>
<style scoped>
.result-json {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  min-width: 0;
  margin: 0;
  padding: 16px;
  background: var(--canvas);
  font:
    12px/1.7 Consolas,
    monospace;
}
</style>
