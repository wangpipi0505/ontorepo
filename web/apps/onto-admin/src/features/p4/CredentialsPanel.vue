<script setup lang="ts">
/** 明文 key 只在创建成功的当前面板中存在；关闭、切空间或卸载即清空。 */
import { computed, onScopeDispose, ref, watch } from 'vue'
import type { GrantUser } from '@onto/api-client'
import type { ApiCredential } from '../../../../../packages/api-client/src/release'
import { useWorkspace } from '../../stores/workspace'
import { useSession } from '../../stores/session'
import { useResource } from '../../composables/useResource'
import { useMutation, confirmAction } from '../../composables/useMutation'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import './p4.css'
const workspace = useWorkspace(),
  session = useSession(),
  api = session.api,
  mutation = useMutation()
const state = useResource<{ users: GrantUser[]; credentials: ApiCredential[] } | null>(null)
const principal = ref(''),
  name = ref(''),
  expiresAt = ref(''),
  key = ref(''),
  copied = ref(false)
const owner = computed(() => workspace.space?.role === 'OWNER' && session.has('grant.read'))
const writable = computed(() => owner.value && workspace.canWriteFor('grant.write'))
let generation = 0
function closeKey() {
  key.value = ''
  copied.value = false
}
onScopeDispose(closeKey)
async function load() {
  const id = workspace.space?.id
  if (!id || !owner.value || mutation.pending.value) return
  await state.load(async () => {
    const [users, credentials] = await Promise.all([
      api.get<GrantUser[]>(`/spaces/${id}/grant-users`),
      api.get<ApiCredential[]>(`/api-credentials?spaceId=${id}`),
    ])
    return { users, credentials }
  })
}
watch(
  () => `${workspace.space?.id}:${owner.value}`,
  () => {
    generation++
    closeKey()
    state.reset()
    principal.value = ''
    name.value = ''
    void load()
  },
  { immediate: true, flush: 'sync' },
)
// 写权限撤销立即清掉一次性明文；仍有读取资格时保留安全目录，禁止迟到创建响应恢复密钥。
watch(
  writable,
  () => {
    generation++
    closeKey()
  },
  { flush: 'sync' },
)
async function create() {
  const id = workspace.space?.id
  if (
    !writable.value ||
    !id ||
    !name.value.trim() ||
    key.value ||
    !state.data.value?.users.some((user) => user.userId === principal.value)
  )
    return
  const ticket = generation
  await mutation.run(async ({ isCurrent }) => {
    const value = await api.post<{ credential: ApiCredential; key: string }>('/api-credentials', {
      spaceId: id,
      principalId: principal.value,
      name: name.value.trim(),
      expiresAt: expiresAt.value ? new Date(expiresAt.value).toISOString() : null,
    })
    if (!isCurrent() || !writable.value || ticket !== generation) return
    key.value = value.key
    copied.value = false
    if (state.data.value)
      state.data.value = {
        ...state.data.value,
        credentials: [value.credential, ...state.data.value.credentials],
      }
  })
}
async function revoke(item: ApiCredential) {
  const context = workspace.captureContext(),
    ticket = generation
  if (
    !writable.value ||
    !(await confirmAction(`撤销“${item.name}”的 API key？后续请求将被拒绝。`)) ||
    !workspace.isCurrentContext(context) ||
    !writable.value ||
    ticket !== generation
  )
    return
  await mutation.run(async ({ isCurrent }) => {
    const value = await api.post<ApiCredential>(`/api-credentials/${item.id}/revoke`, {})
    if (isCurrent() && writable.value && ticket === generation && state.data.value)
      state.data.value = {
        ...state.data.value,
        credentials: state.data.value.credentials.map((row) => (row.id === value.id ? value : row)),
      }
  })
}
async function copy() {
  try {
    await navigator.clipboard.writeText(key.value)
    copied.value = true
  } catch {
    mutation.error.value = '复制失败，请手动选择并保存当前明文。'
  }
}
</script>
<template>
  <section class="p4-page panel stack">
    <div class="row spread">
      <h2>API key</h2>
      <el-button v-if="owner" :disabled="mutation.pending.value || !!key" @click="load">
        刷新凭据目录
      </el-button>
    </div>
    <p class="muted">凭据映射到已有本地用户；消费时仍需有效的端点授权和空间读取授权。</p>
    <p v-if="!owner" class="readonly-note">需空间 OWNER 身份及授权读取权限才能查看消费凭据。</p>
    <AsyncState v-else :loading="state.loading.value" :error="state.error.value" @retry="load">
      <template v-if="state.data.value">
        <p v-if="!writable" class="readonly-note">
          当前只能查看凭据安全目录。创建和撤销需要有效空间及授权写权限。
        </p>
        <article v-for="item in state.data.value.credentials" :key="item.id" class="record">
          <div class="row spread">
            <b>{{ item.name }} · {{ item.status }}</b>
            <el-button
              v-if="writable && item.status === 'ACTIVE'"
              type="danger"
              :disabled="mutation.pending.value"
              @click="revoke(item)"
            >
              撤销
            </el-button>
          </div>
          <p class="muted">
            本地主体 {{ item.principalId }} · 创建 {{ item.createdAt }} · 到期
            {{ item.expiresAt ?? '未设置' }}
          </p>
        </article>
        <p v-if="!state.data.value.credentials.length" class="muted">
          尚无 API key。先确认本地用户已获得消费授权。
        </p>
        <form v-if="writable" class="stack" @submit.prevent="create">
          <div class="form-columns">
            <label>
              本地主体
              <select
                v-model="principal"
                data-testid="credential-principal"
                :disabled="mutation.pending.value || !!key"
              >
                <option value="">请选择已有用户</option>
                <option
                  v-for="user in state.data.value.users"
                  :key="user.userId"
                  :value="user.userId"
                >
                  {{ user.displayName }}（{{ user.username }}）
                </option>
              </select>
            </label>
            <label>
              凭据名称
              <input
                v-model="name"
                data-testid="credential-name"
                maxlength="160"
                :disabled="mutation.pending.value || !!key"
              />
            </label>
          </div>
          <label>
            到期时间（可选）
            <input
              v-model="expiresAt"
              type="datetime-local"
              :disabled="mutation.pending.value || !!key"
            />
          </label>
          <div>
            <el-button
              native-type="submit"
              type="primary"
              :disabled="!principal || !name.trim() || !!key"
              :loading="mutation.pending.value"
            >
              创建 API key
            </el-button>
          </div>
        </form>
      </template>
    </AsyncState>
    <div v-if="key" class="record stack" role="status">
      <b>仅本次展示，请现在保存</b>
      <code>{{ key }}</code>
      <div class="row">
        <el-button @click="copy">{{ copied ? '已复制' : '复制密钥' }}</el-button>
        <el-button @click="closeKey">已保存，关闭明文</el-button>
      </div>
    </div>
    <p v-if="mutation.error.value" class="error-text" role="alert">{{ mutation.error.value }}</p>
  </section>
</template>
