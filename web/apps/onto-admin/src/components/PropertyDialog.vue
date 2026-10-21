<script setup lang="ts">
/** 维护当前类的基础属性；写入携带草稿修订，失败不关闭表单，权限变化立即禁用保存。 */
import { reactive, ref, watch } from 'vue'
import type { OntProperty, RangeType, Space, Codebook } from '@onto/api-client'
import PropertyFacetsFields from '../features/p1/PropertyFacetsFields.vue'
import { useResource } from '../composables/useResource'
import { readDraftSnapshot } from '../composables/readDraftSnapshot'
import { useDraftRevision } from '../composables/useDraftRevision'
import { useSession } from '../stores/session'
import { useWorkspace } from '../stores/workspace'
import { useMutation, notifySaved } from '../composables/useMutation'
const props = defineProps<{ item: OntProperty | null; classId: string; revision?: number }>()
const open = defineModel<boolean>({ required: true }),
  emit = defineEmits<{ saved: [] }>()
const workspace = useWorkspace(),
  api = useSession().api,
  { pending, error, run } = useMutation()
const draft = useDraftRevision()
const fields = ref<InstanceType<typeof PropertyFacetsFields>>()
const references = useResource<{
  books: Codebook[]
  impact: { references: { kind: string; id: string; name: string }[] }
}>({ books: [], impact: { references: [] } })
async function loadReferences() {
  const id = workspace.space?.id
  if (!id) return
  return references.load(async () => {
    const snapshot = await readDraftSnapshot(api, id, async () => {
      const books = await api.get<Codebook[]>(`/codebooks?spaceId=${id}`)
      const impact = props.item
        ? await api.get<{ references: { kind: string; id: string; name: string }[] }>(
            `/properties/${props.item.id}/impact?spaceId=${id}`,
          )
        : { references: [] }
      return { books, impact }
    })
    return snapshot.items
  })
}
const form = reactive({
  name: '',
  displayName: '',
  rangeType: 'STRING' as RangeType,
  required: false,
  description: '',
})
const types: { value: RangeType; label: string }[] = [
  { value: 'STRING', label: '文本 STRING' },
  { value: 'INTEGER', label: '整数 INTEGER' },
  { value: 'DECIMAL', label: '小数 DECIMAL' },
  { value: 'BOOLEAN', label: '布尔 BOOLEAN' },
  { value: 'DATE', label: '日期 DATE' },
]
watch(open, (value) => {
  if (value) {
    // 属性面板可能仍显示旧列表；只能使用读取该列表时的修订，不能借类树刷新后的新修订写旧实体。
    draft.start(props.revision ?? workspace.space?.draftRev ?? 0)
    Object.assign(form, {
      name: props.item?.name || '',
      displayName: props.item?.displayName || '',
      rangeType: props.item?.rangeType || 'STRING',
      required: props.item?.required ?? false,
      description: props.item?.description || '',
    })
    error.value = ''
    void loadReferences()
  }
})
async function save() {
  const space = workspace.space
  if (!space || !workspace.canWrite || !props.classId) return
  if (!form.name.trim()) {
    error.value = '请输入属性名称。'
    return
  }
  const ok = await run(({ isCurrent }) =>
    draft.submit(async (expectedDraftRev) => {
      const body = {
        ...form,
        spaceId: space.id,
        domainClassId: props.classId,
        expectedDraftRev,
        facets: fields.value?.read() || {},
      }
      const next = props.item
        ? await api.put<Space>(`/properties/${props.item.id}`, body)
        : await api.post<Space>('/properties', body)
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
    destroy-on-close
    v-model="open"
    :title="item ? '编辑属性' : '新建属性'"
    width="680px"
    :show-close="!pending"
    :close-on-click-modal="!pending"
    :close-on-press-escape="!pending"
  >
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="属性名称" required>
        <el-input v-model="form.name" maxlength="200" />
      </el-form-item>
      <el-form-item label="显示名称（选填）">
        <el-input v-model="form.displayName" maxlength="200" placeholder="面向业务用户的名称" />
      </el-form-item>
      <el-form-item label="基础类型" required>
        <el-select v-model="form.rangeType" style="width: 100%">
          <el-option
            v-for="type in types"
            :key="type.value"
            :value="type.value"
            :label="type.label"
          />
        </el-select>
      </el-form-item>
      <el-form-item><el-checkbox v-model="form.required">必填属性</el-checkbox></el-form-item>
      <el-form-item label="属性说明">
        <el-input v-model="form.description" type="textarea" :rows="3" maxlength="4000" />
      </el-form-item>
      <p v-if="references.loading.value" class="muted">正在读取码表与引用影响…</p>
      <p v-if="references.error.value" class="error-text" role="alert">
        {{ references.error.value }}
        <el-button text @click="loadReferences">重试读取</el-button>
      </p>
      <div v-if="references.data.value.impact.references.length" class="readonly-note">
        此属性被引用，修改类型或删除可能被阻止：
        <ul>
          <li v-for="reference in references.data.value.impact.references" :key="reference.id">
            {{ reference.kind }} · {{ reference.name }}
          </li>
        </ul>
      </div>
      <PropertyFacetsFields
        ref="fields"
        :facets="item?.facets"
        :books="references.data.value.books"
        :range-type="form.rangeType"
        :required="form.required"
      />
      <p v-if="draft.stale.value" class="error-text" role="alert">
        表单已过期。可复制当前输入，关闭后刷新本页，再重新打开以载入最新内容。
      </p>
      <p v-if="error" class="error-text" role="alert">{{ error }}</p>
      <div class="row">
        <el-button
          native-type="submit"
          type="primary"
          :loading="pending"
          :disabled="
            !workspace.canWrite ||
            draft.stale.value ||
            references.loading.value ||
            !!references.error.value
          "
        >
          保存属性
        </el-button>
        <el-button :disabled="pending" @click="open = false">取消</el-button>
      </div>
    </el-form>
  </el-dialog>
</template>
