<script setup lang="ts">
/** 版本选择只加载真实端点目录；授权、凭据和调用各自持有独立可重试状态。 */
import { computed, ref, watch } from 'vue'
import type { Release, Endpoint } from '../../../../../packages/api-client/src/release'
import { useWorkspace } from '../../stores/workspace'
import { useSession } from '../../stores/session'
import { useResource } from '../../composables/useResource'
import { useMutation, confirmAction } from '../../composables/useMutation'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import EndpointGrantsPanel from './EndpointGrantsPanel.vue'
import CredentialsPanel from './CredentialsPanel.vue'
import ServiceCallPanel from './ServiceCallPanel.vue'
import './p4.css'
const workspace = useWorkspace(),
  session = useSession(),
  api = session.api
const releases = useResource<Release[]>([]),
  endpoints = useResource<Endpoint[]>([]),
  selected = ref(''),
  endpointId = ref(''),
  mutation = useMutation()
const release = computed(() => releases.data.value.find((item) => item.id === selected.value))
const endpoint = computed(() => endpoints.data.value.find((item) => item.id === endpointId.value))
function load() {
  const id = workspace.space?.id
  if (!id) return
  return releases.load(() => api.get<Release[]>(`/releases?spaceId=${id}`))
}
async function loadEndpoints() {
  if (!selected.value) return
  const result = await endpoints.load(() =>
    api.get<Endpoint[]>(`/releases/${selected.value}/endpoints`),
  )
  if (result) endpointId.value = result[0]?.id ?? ''
}
/** 端点级启停只改变本端点可用性；停用后服务端在访问源前拒绝。 */
async function toggleEndpoint() {
  const item = endpoint.value
  if (!item || mutation.pending.value || !session.has('grant.write')) return
  const next = item.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  if (
    !(await confirmAction(
      next === 'DISABLED'
        ? '停用后该端点的所有调用都会被拒绝，确认继续？'
        : '启用该端点并恢复调用？',
    ))
  )
    return
  if (await mutation.run(() => api.put(`/endpoints/${item.id}/status`, { status: next })))
    await loadEndpoints()
}
watch(selected, () => {
  endpointId.value = ''
  endpoints.reset()
  void loadEndpoints()
})
watch(
  () => workspace.space?.id,
  () => {
    selected.value = ''
    endpoints.reset()
    releases.reset()
    void load()
  },
  { immediate: true },
)
async function refresh() {
  selected.value = ''
  await load()
}
</script>
<template>
  <div class="p4-page stack">
    <div class="page-heading">
      <div>
        <h1>服务消费</h1>
        <p class="muted">维护端点授权、一次性密钥，并验证固定正式版本的服务调用。</p>
      </div>
      <el-button @click="refresh">刷新消费目录</el-button>
    </div>
    <section class="panel stack">
      <AsyncState
        :loading="releases.loading.value"
        :error="releases.error.value"
        :empty="!releases.data.value.length"
        empty-text="尚无正式版本。发布后才能管理端点和执行正式服务。"
        @retry="load"
      >
        <label>
          正式版本
          <select v-model="selected">
            <option value="">请选择版本</option>
            <option v-for="item in releases.data.value" :key="item.id" :value="item.id">
              v{{ item.versionNo }} · {{ item.status }}
            </option>
          </select>
        </label>
      </AsyncState>
      <AsyncState
        :loading="endpoints.loading.value"
        :error="endpoints.error.value"
        @retry="loadEndpoints"
      >
        <template v-if="release">
          <p v-if="!endpoints.data.value.length" class="muted">此版本没有可展示的端点。</p>
          <label v-else>
            服务端点
            <select v-model="endpointId">
              <option v-for="item in endpoints.data.value" :key="item.id" :value="item.id">
                {{ item.opCode }} · {{ item.status }}
              </option>
            </select>
          </label>
          <p v-if="endpoint?.description" class="muted">端点说明：{{ endpoint.description }}</p>
          <div v-if="endpoint && release.status === 'READY'" class="row">
            <el-button
              size="small"
              :loading="mutation.pending.value"
              :disabled="!session.has('grant.write')"
              @click="toggleEndpoint"
            >
              {{ endpoint.status === 'ACTIVE' ? '停用此端点' : '启用此端点' }}
            </el-button>
            <span class="muted">
              {{
                endpoint.status === 'ACTIVE'
                  ? '停用后所有调用立即被拒绝，可随时恢复'
                  : '停用中：调用在访问数据源前被拒绝'
              }}
            </span>
          </div>
          <p v-if="mutation.error.value" class="error-text" role="alert">
            {{ mutation.error.value }}
          </p>
        </template>
      </AsyncState>
    </section>
    <EndpointGrantsPanel
      v-if="endpoint && session.has('grant.read')"
      :key="endpoint.id"
      :endpoint="endpoint"
    />
    <ServiceCallPanel
      v-if="release && session.has('service.execute')"
      :key="release.id"
      :release="release"
      :endpoints="endpoints.data.value"
    />
    <CredentialsPanel v-if="session.has('grant.read')" :key="workspace.space?.id" />
  </div>
</template>
