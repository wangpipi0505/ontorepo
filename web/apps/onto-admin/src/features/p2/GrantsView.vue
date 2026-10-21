<script setup lang="ts">
/** 本地身份目录与空间授权分别读取；用户标识只能来自 OWNER 可读的本地目录。 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import type { GrantUser, SpaceGrant, Space } from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useDraftDocument } from './useDraftDocument'
import { FieldError, objectField } from './fields'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
const api = useSession().api
const editor = useDraftDocument(async (id) => {
  const [grants, users] = await Promise.all([
    api.get<SpaceGrant[]>(`/spaces/${id}/grants`),
    api.get<GrantUser[]>(`/spaces/${id}/grant-users`),
  ])
  return { grants, users }
})
const owner = computed(() => editor.workspace.space?.role === 'OWNER')
const users = computed(() => editor.resource.data.value?.items.users || [])
const grants = computed(() => editor.resource.data.value?.items.grants || [])
const userId = ref(''),
  form = reactive({ role: 'VIEWER' as SpaceGrant['role'], scope: 'null', attributes: '{}' })
const errors = reactive<Record<string, string>>({})
function select() {
  const grant = grants.value.find((item) => item.userId === userId.value)
  form.role = grant?.role ?? 'VIEWER'
  form.scope = JSON.stringify(grant?.dataScope ?? null, null, 2)
  form.attributes = JSON.stringify(grant?.attributes ?? {}, null, 2)
  Object.keys(errors).forEach((key) => delete errors[key])
}
watch(userId, select)
async function load() {
  if (!owner.value) return
  if (await editor.load()) {
    userId.value = ''
    select()
  }
}
async function save() {
  if (
    !owner.value ||
    !editor.workspace.canWrite ||
    editor.draft.stale.value ||
    editor.mutation.pending.value
  )
    return
  Object.keys(errors).forEach((key) => delete errors[key])
  try {
    if (!users.value.some((item) => item.userId === userId.value))
      throw new FieldError('user', '请选择已有本地用户。')
    const dataScope =
      form.scope.trim() === 'null' ? null : objectField(form.scope, 'scope', '数据范围')
    const attributes = objectField(form.attributes, 'attributes', '主体属性')
    const id = editor.workspace.space?.id
    if (
      id &&
      (await editor.save((expectedDraftRev) =>
        api.put<Space>(`/spaces/${id}/grants/${userId.value}`, {
          expectedDraftRev,
          role: form.role,
          dataScope,
          attributes,
        }),
      ))
    )
      await load()
  } catch (cause) {
    if (cause instanceof FieldError) errors[cause.field] = cause.message
  }
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>主体授权</h1>
      <p class="muted">为本地用户维护空间角色、数据范围与可信属性。</p>
    </div>
    <el-button v-if="owner" :disabled="editor.mutation.pending.value" @click="load">
      刷新授权
    </el-button>
  </div>
  <p v-if="!owner" class="readonly-note">仅空间所有者（OWNER）可查看和维护主体授权。</p>
  <AsyncState
    v-else
    :loading="editor.resource.loading.value"
    :error="editor.resource.error.value"
    @retry="load"
  >
    <div v-if="editor.resource.data.value" class="stack">
      <section class="panel overflow">
        <h2>当前授权</h2>
        <el-table :data="grants">
          <el-table-column prop="displayName" label="用户" min-width="130" />
          <el-table-column prop="username" label="登录名" min-width="130" />
          <el-table-column prop="role" label="角色" width="100" />
          <el-table-column label="维护" width="90">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                :disabled="editor.mutation.pending.value || !editor.workspace.canWrite"
                @click="userId = row.userId"
              >
                编辑
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
      <section class="panel">
        <h2>维护授权</h2>
        <el-form
          label-position="top"
          :disabled="!editor.workspace.canWrite || editor.mutation.pending.value"
          @submit.prevent="save"
        >
          <el-form-item label="本地用户" :error="errors.user">
            <el-select v-model="userId" filterable placeholder="选择本地用户">
              <el-option
                v-for="user in users"
                :key="user.userId"
                :value="user.userId"
                :label="`${user.displayName}（${user.username}）`"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="空间角色">
            <el-select v-model="form.role">
              <el-option
                v-for="role in ['VIEWER', 'EDITOR', 'OWNER']"
                :key="role"
                :value="role"
                :label="role"
              />
            </el-select>
          </el-form-item>
          <div class="form-columns">
            <el-form-item label="数据范围（JSON 对象或 null）" :error="errors.scope">
              <el-input v-model="form.scope" type="textarea" :rows="6" />
            </el-form-item>
            <el-form-item label="主体属性（JSON 对象）" :error="errors.attributes">
              <el-input v-model="form.attributes" type="textarea" :rows="6" />
            </el-form-item>
          </div>
          <p class="muted">
            null
            表示已授权用户不限数据范围；缺少空间授权仍会被拒绝。属性类型由服务器按守卫定义校验，执行请求不会提交这些属性。不能移除最后一个
            OWNER。
          </p>
          <p v-if="editor.draft.stale.value" class="error-text" role="alert">
            授权表单已过期，输入已保留。请复制后刷新授权再编辑。
          </p>
          <p v-if="editor.mutation.error.value" class="error-text" role="alert">
            {{ editor.mutation.error.value }}
          </p>
          <el-button
            native-type="submit"
            type="primary"
            :disabled="!userId || editor.draft.stale.value"
            :loading="editor.mutation.pending.value"
          >
            保存主体授权
          </el-button>
        </el-form>
      </section>
    </div>
  </AsyncState>
</template>
