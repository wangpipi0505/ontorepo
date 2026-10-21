<script setup lang="ts">
import { computed, reactive } from 'vue'
import type { PermissionDefinition, SystemRole } from '@onto/api-client'
import PermissionPicker from './PermissionPicker.vue'
import { useSystemEditor } from './useSystemEditor'
const props = defineProps<{ role: SystemRole | null; definitions: PermissionDefinition[] }>(),
  emit = defineEmits<{ saved: []; close: [] }>()
const editor = useSystemEditor('system.role.write'),
  revision = props.role?.revision
const form = reactive({
  code: props.role?.code || '',
  name: props.role?.name || '',
  description: props.role?.description || '',
  status: props.role?.status || 'ACTIVE',
  permissions: [...(props.role?.permissions || [])],
})
const readOnly = computed(() => !!props.role?.builtIn || !editor.session.has('system.role.write'))
async function save() {
  if (props.role?.builtIn) return
  if (!props.role && !/^[A-Za-z][A-Za-z0-9_-]{0,79}$/.test(form.code.trim())) {
    editor.mutation.error.value =
      '角色编码须以字母开头，仅含字母、数字、下划线或短横线，最多 80 字符。'
    return
  }
  if (!form.code.trim() || !form.name.trim()) {
    editor.mutation.error.value = '请填写角色编码和名称。'
    return
  }
  await editor.save(
    () =>
      props.role
        ? editor.session.api.put(`/system/roles/${props.role.id}`, {
            name: form.name.trim(),
            description: form.description,
            status: form.status,
            permissions: form.permissions,
            expectedRevision: revision,
          })
        : editor.session.api.post('/system/roles', {
            code: form.code.trim(),
            name: form.name.trim(),
            description: form.description,
            permissions: form.permissions,
          }),
    () => emit('saved'),
  )
}
</script>
<template>
  <el-form label-position="top" @submit.prevent="save">
    <el-form-item label="角色编码" required>
      <el-input
        v-model="form.code"
        :disabled="!!role || editor.mutation.pending.value"
        maxlength="80"
      />
    </el-form-item>
    <el-form-item label="角色名称" required>
      <el-input
        v-model="form.name"
        :disabled="readOnly || editor.mutation.pending.value"
        maxlength="200"
      />
    </el-form-item>
    <el-form-item label="说明">
      <el-input
        v-model="form.description"
        type="textarea"
        :disabled="readOnly || editor.mutation.pending.value"
        maxlength="2000"
      />
    </el-form-item>
    <el-form-item v-if="role" label="角色状态">
      <el-select v-model="form.status" :disabled="readOnly || editor.mutation.pending.value">
        <el-option value="ACTIVE" label="启用" />
        <el-option value="DISABLED" label="停用（保留成员关系，停止授予权限）" />
      </el-select>
    </el-form-item>
    <PermissionPicker
      v-model="form.permissions"
      :definitions="definitions"
      :disabled="readOnly || editor.mutation.pending.value"
    />
    <p v-if="editor.stale.value" class="readonly-note">
      角色已被修改。请保留输入，关闭后刷新列表并重新编辑。
    </p>
    <p v-if="editor.mutation.error.value" class="error-text" role="alert">
      {{ editor.mutation.error.value }}
    </p>
    <div class="row">
      <el-button :disabled="editor.mutation.pending.value" @click="emit('close')">关闭</el-button>
      <el-button
        v-if="!readOnly"
        native-type="submit"
        type="primary"
        :loading="editor.mutation.pending.value"
        :disabled="editor.stale.value || !editor.session.has('system.role.write')"
      >
        保存角色
      </el-button>
    </div>
  </el-form>
</template>
