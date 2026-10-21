<script setup lang="ts">
/** 空间创建与编辑表单：命名空间创建后不可改；冲突保留输入并锁定，刷新列表后重新打开才可编辑。 */
import { reactive, watch } from 'vue'
import type { Space } from '@onto/api-client'
import { useDraftRevision } from '../composables/useDraftRevision'
import { useSession } from '../stores/session'
import { useMutation, notifySaved } from '../composables/useMutation'
const props = defineProps<{ space: Space | null }>()
const open = defineModel<boolean>({ required: true }),
  emit = defineEmits<{ saved: [] }>()
const form = reactive({ code: '', name: '', namespaceUri: '', domain: '' })
const session = useSession(),
  api = session.api
const { pending, error, run } = useMutation()
const draft = useDraftRevision()
watch(open, (value) => {
  if (value) {
    Object.assign(form, {
      code: props.space?.code || '',
      name: props.space?.name || '',
      namespaceUri: props.space?.namespaceUri || '',
      domain: props.space?.domain || '',
    })
    draft.start(props.space?.draftRev || 0)
    error.value = ''
  }
})
async function save() {
  if (!session.has(props.space ? 'space.write' : 'space.create')) return
  if (!props.space && !/^[A-Za-z][A-Za-z0-9_-]{0,79}$/.test(form.code.trim())) {
    error.value = '空间编码须以字母开头，仅含字母、数字、下划线或短横线，最多 80 字符。'
    return
  }
  if (
    !form.name.trim() ||
    (!props.space && (!form.code.trim() || !/^https?:\/\/[^\s]+[\/#]$/.test(form.namespaceUri)))
  ) {
    error.value = '请填写名称、空间编码及以 / 或 # 结尾的绝对 HTTP(S) 命名空间。'
    return
  }
  const ok = await run(() =>
    draft.submit(async (expectedDraftRev) => {
      if (props.space)
        await api.put(`/spaces/${props.space.id}`, {
          name: form.name,
          domain: form.domain,
          expectedDraftRev,
        })
      else await api.post('/spaces', form)
    }),
  )
  if (ok) {
    open.value = false
    notifySaved()
    emit('saved')
  }
}
</script>
<template>
  <el-dialog
    v-model="open"
    :title="space ? '编辑空间' : '新建空间'"
    width="540px"
    :close-on-click-modal="!pending"
    :close-on-press-escape="!pending"
    :show-close="!pending"
  >
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="空间编码" required>
        <el-input
          v-model="form.code"
          :disabled="!!space"
          maxlength="80"
          placeholder="例如 equipment"
        />
      </el-form-item>
      <el-form-item label="空间名称" required>
        <el-input v-model="form.name" maxlength="200" />
      </el-form-item>
      <el-form-item label="命名空间 URI" required>
        <el-input
          v-model="form.namespaceUri"
          maxlength="1000"
          :disabled="!!space"
          placeholder="https://example.org/ontology/equipment/"
        />
        <span class="muted">创建后不可修改，用于生成稳定资源标识。</span>
      </el-form-item>
      <el-form-item label="领域说明（选填，最多 200 字符）">
        <el-input v-model="form.domain" type="textarea" :rows="3" maxlength="200" />
      </el-form-item>
      <p v-if="draft.stale.value" class="error-text" role="alert">
        表单已过期。可复制当前输入，关闭后刷新本页，再重新打开以载入最新内容。
      </p>
      <p v-if="error" class="error-text" role="alert">{{ error }}</p>
      <div class="row">
        <el-button
          type="primary"
          native-type="submit"
          :loading="pending"
          :disabled="draft.stale.value"
        >
          保存空间
        </el-button>
        <el-button :disabled="pending" @click="open = false">取消</el-button>
      </div>
    </el-form>
  </el-dialog>
</template>
