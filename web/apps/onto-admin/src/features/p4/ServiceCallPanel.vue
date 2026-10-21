<script setup lang="ts">
/** 正式调用使用 TOKEN 的实际主体，成功证据必须来自服务返回且匹配当前正式版本。 */
import { computed, ref, shallowRef, watch } from 'vue'
import type { Envelope } from '@onto/api-client'
import type { Endpoint, Release } from '../../../../../packages/api-client/src/release'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import { useMutation } from '../../composables/useMutation'
import ParameterFields from './ParameterFields.vue'
import { parameterObject, type ParameterRow } from './parameters'
const props = defineProps<{ release: Release; endpoints: Endpoint[] }>()
const session = useSession(),
  api = session.api,
  workspace = useWorkspace(),
  mutation = useMutation()
const code = ref(''),
  rows = ref<ParameterRow[]>([]),
  result = shallowRef<Envelope<unknown> | null>(null)
const inputMode = ref<'fields' | 'json'>('fields'),
  jsonInput = ref('{}')
const endpoint = computed(() => props.endpoints.find((item) => item.opCode === code.value))
const allowed = computed(
  () =>
    session.has('service.execute') &&
    props.release.status === 'READY' &&
    !!workspace.space &&
    !!endpoint.value &&
    endpoint.value.status === 'ACTIVE',
)
let generation = 0
watch(
  () => props.release.id,
  () => {
    generation++
    code.value = ''
    rows.value = []
    result.value = null
    mutation.error.value = ''
  },
)
watch(code, () => {
  generation++
  rows.value = []
  jsonInput.value = '{}'
  inputMode.value = 'fields'
  result.value = null
  mutation.error.value = ''
})
function readInput(): unknown {
  if (inputMode.value === 'fields') return parameterObject(rows.value)
  try {
    return JSON.parse(jsonInput.value) as unknown
  } catch {
    throw new Error('操作输入须为有效 JSON 值。请检查格式后重试。')
  }
}
async function execute() {
  if (!allowed.value) return
  const release = props.release,
    opCode = code.value,
    ticket = generation,
    spaceCode = workspace.space?.code
  result.value = null
  await mutation.run(async ({ isCurrent }) => {
    const response = await api.requestEnvelope<unknown>(
      `/internal/releases/${release.id}/ops/${encodeURIComponent(opCode)}`,
      'POST',
      readInput(),
    )
    if (!isCurrent() || ticket !== generation) return
    const meta = response.meta
    if (
      !meta ||
      typeof meta !== 'object' ||
      !('preview' in meta) ||
      meta.preview !== false ||
      !('releaseId' in meta) ||
      meta.releaseId !== release.id ||
      !('version' in meta) ||
      meta.version !== `v${release.versionNo}` ||
      !('contentDigest' in meta) ||
      meta.contentDigest !== release.bundleSha256 ||
      !('spaceCode' in meta) ||
      meta.spaceCode !== spaceCode
    )
      throw new Error('服务返回的正式版本证据不完整或与所选版本不符，请刷新版本后重试。')
    result.value = response
  })
}
</script>
<template>
  <section class="p4-page panel stack">
    <h2>正式服务调用</h2>
    <p class="muted">
      以当前登录用户调用指定版本。OWNER 身份仍需有效的端点授权与空间授权；所有可信守卫由服务端取得。
    </p>
    <p v-if="release.status !== 'READY'" class="readonly-note">
      {{ release.status }} 版本不可消费。
    </p>
    <label>
      正式操作
      <select
        v-model="code"
        data-testid="service-operation"
        :disabled="mutation.pending.value || release.status !== 'READY'"
      >
        <option value="">请选择正式操作</option>
        <option v-for="item in endpoints" :key="item.id" :value="item.opCode">
          {{ item.opCode }} · {{ item.status }}
        </option>
      </select>
    </label>
    <p v-if="endpoint" class="identity">固定版本路由：{{ endpoint.route }}</p>
    <label>
      操作输入方式
      <select
        v-model="inputMode"
        data-testid="service-input-mode"
        :disabled="mutation.pending.value || !allowed"
      >
        <option value="fields">逐字段填写业务对象</option>
        <option value="json">直接输入 JSON 值（支持数组、标量和 null）</option>
      </select>
    </label>
    <ParameterFields
      v-if="inputMode === 'fields'"
      v-model="rows"
      :disabled="mutation.pending.value || !allowed"
      add-label="添加业务参数"
    />
    <label v-else>
      操作输入（JSON 值）
      <textarea
        v-model="jsonInput"
        data-testid="service-json-input"
        rows="5"
        :disabled="mutation.pending.value || !allowed"
      />
    </label>
    <div>
      <el-button
        type="primary"
        :disabled="!allowed"
        :loading="mutation.pending.value"
        @click="execute"
      >
        调用正式服务
      </el-button>
    </div>
    <p v-if="mutation.error.value" class="error-text" role="alert">{{ mutation.error.value }}</p>
    <div v-if="result" data-testid="service-result" class="stack">
      <h3>业务数据</h3>
      <pre>{{ JSON.stringify(result.data, null, 2) }}</pre>
      <h3>正式版本元数据</h3>
      <pre>{{ JSON.stringify(result.meta, null, 2) }}</pre>
    </div>
  </section>
</template>
