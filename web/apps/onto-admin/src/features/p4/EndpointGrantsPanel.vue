<script setup lang="ts">
/** 端点授权和空间授权是独立事实；不存在记录必须显示拒绝，不等价于 NULL 范围。 */
import { computed, ref, watch } from 'vue'
import type { GrantUser, SpaceGrant } from '@onto/api-client'
import type {
  Endpoint,
  EndpointGrant,
  EndpointRoleGrant,
} from '../../../../../packages/api-client/src/release'
import { useWorkspace } from '../../stores/workspace'
import { useSession } from '../../stores/session'
import { useResource } from '../../composables/useResource'
import { useMutation } from '../../composables/useMutation'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import ParameterFields from './ParameterFields.vue'
import { parameterObject, parameterRows, type ParameterRow } from './parameters'
const props = defineProps<{ endpoint: Endpoint }>()
const workspace = useWorkspace(),
  session = useSession(),
  api = session.api,
  mutation = useMutation()
const state = useResource<{
  grants: EndpointGrant[]
  roleGrants: EndpointRoleGrant[]
  users: GrantUser[]
  spaceGrants: SpaceGrant[]
} | null>(null)
const userId = ref(''),
  roleCode = ref(''),
  roleStatus = ref<EndpointRoleGrant['status']>('ACTIVE'),
  roleExpiresAt = ref(''),
  scope = ref<'unlimited' | 'limited'>('limited'),
  rows = ref<ParameterRow[]>([]),
  status = ref<EndpointGrant['status']>('ACTIVE'),
  expiresAt = ref('')
const owner = computed(() => workspace.space?.role === 'OWNER' && session.has('grant.read'))
const writable = computed(() => owner.value && workspace.canWriteFor('grant.write'))
const selected = computed(() =>
  state.data.value?.grants.find((item) => item.userId === userId.value),
)
const spaceGrant = computed(() =>
  state.data.value?.spaceGrants.find((item) => item.userId === userId.value),
)
let generation = 0
async function load() {
  const id = workspace.space?.id,
    endpointId = props.endpoint.id
  if (!id || !owner.value || mutation.pending.value) return
  await state.load(async () => {
    const [grants, roleGrants, users, spaceGrants] = await Promise.all([
      api.get<EndpointGrant[]>(`/endpoints/${endpointId}/grants`),
      api.get<EndpointRoleGrant[]>(`/endpoints/${endpointId}/role-grants`),
      api.get<GrantUser[]>(`/spaces/${id}/grant-users`),
      api.get<SpaceGrant[]>(`/spaces/${id}/grants`),
    ])
    return { grants, roleGrants, users, spaceGrants }
  })
}
function select() {
  const item = selected.value
  scope.value = item?.dataScope === null ? 'unlimited' : 'limited'
  rows.value = parameterRows(item?.dataScope ?? null)
  status.value = item?.status ?? 'ACTIVE'
  expiresAt.value = item?.expiresAt
    ? new Date(
        new Date(item.expiresAt).getTime() - new Date(item.expiresAt).getTimezoneOffset() * 60000,
      )
        .toISOString()
        .slice(0, 16)
    : ''
}
watch(userId, select)
watch(
  () => `${workspace.space?.id}:${props.endpoint.id}:${owner.value}`,
  () => {
    generation++
    state.reset()
    userId.value = ''
    select()
    void load()
  },
  { immediate: true, flush: 'sync' },
)
// 失去写权限只关闭编辑区，安全目录仍可只读；代次阻止撤权前请求在重新授权后回写。
watch(
  writable,
  () => {
    generation++
    userId.value = ''
    select()
  },
  { flush: 'sync' },
)
async function refresh() {
  await load()
  select()
}
async function save() {
  if (!writable.value || !state.data.value?.users.some((user) => user.userId === userId.value))
    return
  const endpointId = props.endpoint.id,
    principal = userId.value,
    ticket = generation
  await mutation.run(async ({ isCurrent }) => {
    const value = await api.put<EndpointGrant>(`/endpoints/${endpointId}/grants/${principal}`, {
      dataScope: scope.value === 'unlimited' ? null : parameterObject(rows.value),
      expiresAt: expiresAt.value ? new Date(expiresAt.value).toISOString() : null,
      status: status.value,
    })
    if (!isCurrent() || !writable.value || ticket !== generation || !state.data.value) return
    state.data.value = {
      ...state.data.value,
      grants: [...state.data.value.grants.filter((item) => item.userId !== value.userId), value],
    }
  })
}
async function saveRole() {
  const code = roleCode.value.trim()
  if (!writable.value || !code) return
  const endpointId = props.endpoint.id,
    ticket = generation
  await mutation.run(async ({ isCurrent }) => {
    const value = await api.put<EndpointRoleGrant>(
      `/endpoints/${endpointId}/role-grants/${encodeURIComponent(code)}`,
      {
        dataScope: null,
        expiresAt: roleExpiresAt.value ? new Date(roleExpiresAt.value).toISOString() : null,
        status: roleStatus.value,
      },
    )
    if (!isCurrent() || !writable.value || ticket !== generation || !state.data.value) return
    state.data.value = {
      ...state.data.value,
      roleGrants: [
        ...state.data.value.roleGrants.filter((item) => item.roleCode !== value.roleCode),
        value,
      ],
    }
  })
}
</script>
<template>
  <section class="p4-page panel stack">
    <div class="row spread">
      <h2>端点授权 · {{ endpoint.opCode }}</h2>
      <el-button v-if="owner" :disabled="mutation.pending.value" @click="refresh">
        刷新端点授权
      </el-button>
    </div>
    <p class="muted">ACTIVE 端点表示服务启用，实际消费仍需通过六步授权。</p>
    <p v-if="!owner" class="readonly-note">需空间 OWNER 身份及授权读取权限才能查看端点授权。</p>
    <AsyncState v-else :loading="state.loading.value" :error="state.error.value" @retry="refresh">
      <div v-if="state.data.value" class="stack">
        <p v-if="!writable" class="readonly-note">
          当前只能查看端点授权。修改需要有效空间及授权写权限。
        </p>
        <article v-for="grant in state.data.value.grants" :key="grant.userId" class="record">
          <div class="row spread">
            <b>{{ grant.displayName }} · {{ grant.status }}</b>
            <el-button
              v-if="writable"
              :disabled="mutation.pending.value"
              @click="userId = grant.userId"
            >
              编辑端点授权
            </el-button>
          </div>
          <p class="muted">
            端点范围：{{
              grant.dataScope === null ? '该已存在授权不限范围' : JSON.stringify(grant.dataScope)
            }}
            · 到期 {{ grant.expiresAt ?? '未设置' }}
          </p>
        </article>
        <section class="stack">
          <h3>角色级授予</h3>
          <p class="muted">
            角色授予按用户的 ACTIVE 角色并集生效，并与用户授予的数据范围求交；角色编码来自角色与权限目录。
          </p>
          <article v-for="grant in state.data.value.roleGrants" :key="grant.roleCode" class="record">
            <b>{{ grant.roleName }}（{{ grant.roleCode }}） · {{ grant.status }}</b>
            <p class="muted">
              端点范围：{{
                grant.dataScope === null ? '已存在授权不限范围' : JSON.stringify(grant.dataScope)
              }}
              · 到期 {{ grant.expiresAt ?? '未设置' }}
            </p>
          </article>
          <p v-if="writable" class="muted">新增或更新角色授予：</p>
          <div v-if="writable" class="form-columns">
            <label>
              角色编码
              <input v-model="roleCode" placeholder="例如 MEMBER" :disabled="mutation.pending.value" />
            </label>
            <label>
              授权状态
              <select v-model="roleStatus" :disabled="mutation.pending.value">
                <option value="ACTIVE">ACTIVE</option>
                <option value="REVOKED">REVOKED</option>
              </select>
            </label>
            <label>
              到期时间（可选）
              <input
                v-model="roleExpiresAt"
                type="datetime-local"
                :disabled="mutation.pending.value"
              />
            </label>
            <el-button
              :disabled="mutation.pending.value || !roleCode.trim()"
              @click="saveRole"
            >
              保存角色授予
            </el-button>
          </div>
        </section>
        <label v-if="writable">
          本地用户
          <select v-model="userId" data-testid="endpoint-user" :disabled="mutation.pending.value">
            <option value="">请选择已有本地用户</option>
            <option v-for="user in state.data.value.users" :key="user.userId" :value="user.userId">
              {{ user.displayName }}（{{ user.username }}）
            </option>
          </select>
        </label>
        <template v-if="writable && userId">
          <div class="form-columns">
            <div class="record">
              <h3>端点授权记录</h3>
              <p>{{ selected ? selected.status : '缺少记录：拒绝消费' }}</p>
            </div>
            <div class="record">
              <h3>空间授权记录</h3>
              <p>
                {{
                  spaceGrant
                    ? `${spaceGrant.role} · ${spaceGrant.dataScope === null ? '已授权且范围不限' : JSON.stringify(spaceGrant.dataScope)}`
                    : '缺少空间授权：拒绝消费'
                }}
              </p>
            </div>
          </div>
          <div class="form-columns">
            <label>
              授权状态
              <select v-model="status" :disabled="mutation.pending.value">
                <option value="ACTIVE">ACTIVE</option>
                <option value="REVOKED">REVOKED</option>
              </select>
            </label>
            <label>
              到期时间（可选）
              <input v-model="expiresAt" type="datetime-local" :disabled="mutation.pending.value" />
            </label>
          </div>
          <label>
            端点数据范围
            <select v-model="scope" data-testid="endpoint-scope" :disabled="mutation.pending.value">
              <option value="limited">指定范围</option>
              <option value="unlimited">此端点授权不额外收窄范围（null）</option>
            </select>
          </label>
          <ParameterFields
            v-if="scope === 'limited'"
            v-model="rows"
            :disabled="mutation.pending.value"
            add-label="添加范围字段"
          />
          <p class="notice">
            null
            仅对已存在的有效端点授权表示范围不限。实际数据范围取策略、端点、空间范围和源白名单的交集；保存不会修改空间角色或冻结守卫。
          </p>
          <div>
            <el-button type="primary" :loading="mutation.pending.value" @click="save">
              保存端点授权
            </el-button>
          </div>
        </template>
      </div>
    </AsyncState>
    <p v-if="mutation.error.value" class="error-text" role="alert">{{ mutation.error.value }}</p>
  </section>
</template>
