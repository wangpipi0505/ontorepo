<script setup lang="ts">
import { reactive } from 'vue'
import type { SystemRole, SystemUser } from '@onto/api-client'
import { useSystemEditor } from './useSystemEditor'
const props = defineProps<{ user: SystemUser | null; roles: SystemRole[] }>()
const emit = defineEmits<{ saved: []; close: [] }>()
const editor = useSystemEditor('system.user.write')
// 本表单随弹窗销毁；修订取自打开时的实体，刷新列表不能替换正在编辑的基线。
const revision = props.user?.revision
const form = reactive({
  username: props.user?.username || '',
  displayName: props.user?.displayName || '',
  status: props.user?.status || 'ACTIVE',
  roleIds: props.user?.roles.map((role) => role.id) || [],
  password: '',
})
async function save() {
  if (!form.username.trim() || !form.displayName.trim()) {
    editor.mutation.error.value = '请填写账号和显示名称。'
    return
  }
  if (
    !props.user &&
    (form.password.length < 12 || new TextEncoder().encode(form.password).length > 72)
  ) {
    editor.mutation.error.value = '密码至少 12 个字符，UTF-8 编码不得超过 72 字节。'
    return
  }
  await editor.save(
    () =>
      props.user
        ? editor.session.api.put(`/system/users/${props.user.id}`, {
            displayName: form.displayName.trim(),
            status: form.status,
            roleIds: form.roleIds,
            expectedRevision: revision,
          })
        : editor.session.api.post('/system/users', {
            username: form.username.trim(),
            displayName: form.displayName.trim(),
            password: form.password,
            roleIds: form.roleIds,
          }),
    () => {
      form.password = ''
      emit('saved')
    },
  )
}
</script>
<template>
  <el-form label-position="top" @submit.prevent="save">
    <el-form-item label="账号" required>
      <el-input
        v-model="form.username"
        :disabled="!!user || editor.mutation.pending.value"
        maxlength="100"
        autocomplete="off"
      />
    </el-form-item>
    <el-form-item label="显示名称" required>
      <el-input
        v-model="form.displayName"
        :disabled="editor.mutation.pending.value"
        maxlength="200"
      />
    </el-form-item>
    <el-form-item v-if="!user" label="初始密码" required>
      <el-input
        v-model="form.password"
        type="password"
        show-password
        autocomplete="new-password"
        :disabled="editor.mutation.pending.value"
      />
    </el-form-item>
    <el-form-item v-else label="账号状态">
      <el-select v-model="form.status" :disabled="editor.mutation.pending.value">
        <el-option value="ACTIVE" label="启用" />
        <el-option value="DISABLED" label="停用（撤销现有会话）" />
      </el-select>
    </el-form-item>
    <el-form-item label="角色（可多选）">
      <el-select
        v-model="form.roleIds"
        multiple
        filterable
        :disabled="editor.mutation.pending.value || !editor.session.has('system.role.read')"
        style="width: 100%"
      >
        <el-option
          v-for="role in roles"
          :key="role.id"
          :value="role.id"
          :disabled="role.status !== 'ACTIVE' && !form.roleIds.includes(role.id)"
          :label="`${role.name}${role.status === 'DISABLED' ? '（已停用）' : ''}`"
        />
      </el-select>
    </el-form-item>
    <p class="muted">
      多个有效角色的权限合并生效。无角色账号仅能查看自身资料与退出；停用角色不授予权限。
    </p>
    <p v-if="editor.stale.value" class="readonly-note">
      记录已被修改。请复制保留输入，关闭后刷新列表并重新编辑。
    </p>
    <p v-if="editor.mutation.error.value" class="error-text" role="alert">
      {{ editor.mutation.error.value }}
    </p>
    <div class="row">
      <el-button :disabled="editor.mutation.pending.value" @click="emit('close')">取消</el-button>
      <el-button
        native-type="submit"
        type="primary"
        :loading="editor.mutation.pending.value"
        :disabled="editor.stale.value || !editor.session.has('system.user.write')"
      >
        保存用户
      </el-button>
    </div>
  </el-form>
</template>
