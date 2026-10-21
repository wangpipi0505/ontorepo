<script setup lang="ts">
import { ref } from 'vue'
import type { SystemUser } from '@onto/api-client'
import { useSystemEditor } from './useSystemEditor'
const props = defineProps<{ user: SystemUser }>(),
  emit = defineEmits<{ saved: []; close: [] }>()
const editor = useSystemEditor('system.user.write'),
  password = ref(''),
  confirmed = ref(false)
const revision = props.user.revision
async function save() {
  if (!confirmed.value) return
  if (password.value.length < 12 || new TextEncoder().encode(password.value).length > 72) {
    editor.mutation.error.value = '密码至少 12 个字符，UTF-8 编码不得超过 72 字节。'
    return
  }
  await editor.save(
    () =>
      editor.session.api.post(`/system/users/${props.user.id}/password`, {
        password: password.value,
        expectedRevision: revision,
      }),
    () => {
      password.value = ''
      emit('saved')
    },
  )
}
</script>
<template>
  <el-form label-position="top" @submit.prevent="save">
    <p>重置 {{ user.displayName }}（{{ user.username }}）的密码并撤销该账号现有会话。</p>
    <el-form-item label="新密码" required>
      <el-input
        v-model="password"
        type="password"
        show-password
        autocomplete="new-password"
        :disabled="editor.mutation.pending.value"
      />
    </el-form-item>
    <el-checkbox v-model="confirmed" :disabled="editor.mutation.pending.value">
      我确认重置密码并使旧会话失效
    </el-checkbox>
    <p v-if="editor.stale.value" class="readonly-note">
      账号已被修改，请关闭并刷新列表后重新操作。
    </p>
    <p v-if="editor.mutation.error.value" class="error-text" role="alert">
      {{ editor.mutation.error.value }}
    </p>
    <div class="row">
      <el-button :disabled="editor.mutation.pending.value" @click="emit('close')">取消</el-button>
      <el-button
        native-type="submit"
        type="danger"
        :loading="editor.mutation.pending.value"
        :disabled="!confirmed || editor.stale.value || !editor.session.has('system.user.write')"
      >
        确认重置密码
      </el-button>
    </div>
  </el-form>
</template>
