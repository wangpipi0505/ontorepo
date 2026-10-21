<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import type { OntClass, Space } from '@onto/api-client'
import { useDraftRevision } from '../composables/useDraftRevision'
import { useSession } from '../stores/session'
import { useWorkspace } from '../stores/workspace'
import { useMutation, notifySaved } from '../composables/useMutation'
import { canReparent } from '../composables/classTree'
/** 对象编辑与键盘父级调整入口；下拉禁用当前对象及其后代，服务端再校验真实层级。 */
const props = defineProps<{ item: OntClass | null; classes: OntClass[]; revision?: number }>()
const open = defineModel<boolean>({ required: true }),
  emit = defineEmits<{ saved: [] }>()
const workspace = useWorkspace(),
  api = useSession().api,
  { pending, error, run } = useMutation()
const draft = useDraftRevision()
const form = reactive({
  name: '',
  parentId: null as string | null,
  description: '',
  deprecated: false,
})
const allowedParents = computed(() =>
  props.classes.map((item) => ({
    ...item,
    disabled: !!props.item && !canReparent(props.classes, props.item.id, item.id),
  })),
)
watch(open, (value) => {
  if (value) {
    // 类树与属性面板可独立刷新；旧类只能携带读取这份类树时的修订。
    draft.start(props.revision ?? workspace.space?.draftRev ?? 0)
    Object.assign(form, {
      name: props.item?.name || '',
      parentId: props.item?.parentId || null,
      description: props.item?.description || '',
      deprecated: props.item?.deprecated ?? false,
    })
    error.value = ''
  }
})
async function save() {
  const space = workspace.space
  if (!space || !workspace.canWrite) return
  if (!form.name.trim()) {
    error.value = '请输入对象名称。'
    return
  }
  if (props.item && !canReparent(props.classes, props.item.id, form.parentId)) {
    error.value = '父级不能是当前对象或其后代。'
    return
  }
  const ok = await run(({ isCurrent }) =>
    draft.submit(async (expectedDraftRev) => {
      const body = { ...form, spaceId: space.id, expectedDraftRev }
      const next = props.item
        ? await api.put<Space>(`/classes/${props.item.id}`, body)
        : await api.post<Space>('/classes', body)
      if (isCurrent()) workspace.accept(next)
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
    :title="item ? '编辑对象' : '新建对象'"
    width="520px"
    :show-close="!pending"
    :close-on-click-modal="!pending"
    :close-on-press-escape="!pending"
  >
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="对象名称" required>
        <el-input v-model="form.name" maxlength="200" placeholder="例如：设备" />
      </el-form-item>
      <el-form-item label="父级对象">
        <el-select
          v-model="form.parentId"
          clearable
          placeholder="顶层对象"
          style="width: 100%"
          @clear="form.parentId = null"
        >
          <el-option
            v-for="parent in allowedParents"
            :key="parent.id"
            :value="parent.id"
            :label="parent.name"
            :disabled="parent.disabled"
          />
        </el-select>
        <span class="muted">可用键盘选择父级；留空表示顶层对象。</span>
      </el-form-item>
      <el-form-item label="对象说明">
        <el-input v-model="form.description" type="textarea" :rows="3" maxlength="4000" />
      </el-form-item>
      <el-form-item>
        <el-checkbox v-model="form.deprecated">标记为已弃用</el-checkbox>
      </el-form-item>
      <p v-if="draft.stale.value" class="error-text" role="alert">
        表单已过期。可复制当前输入，关闭后刷新本页，再重新打开以载入最新内容。
      </p>
      <p v-if="error" role="alert" class="error-text">{{ error }}</p>
      <div class="row">
        <el-button
          native-type="submit"
          type="primary"
          :loading="pending"
          :disabled="!workspace.canWrite || draft.stale.value"
        >
          保存对象
        </el-button>
        <el-button :disabled="pending" @click="open = false">取消</el-button>
      </div>
    </el-form>
  </el-dialog>
</template>
