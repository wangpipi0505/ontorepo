<script setup lang="ts">
import { ref, watch } from 'vue'
import type { OntClass, OntProperty, Space } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useWorkspace } from '../stores/workspace'
import { useSession } from '../stores/session'
import { useResource } from '../composables/useResource'
import { useMutation, confirmAction } from '../composables/useMutation'
import { useEntityFocus } from '../composables/useEntityFocus'
import PropertyDialog from './PropertyDialog.vue'
import { readDraftSnapshot } from '../composables/readDraftSnapshot'
/** 面板只加载当前类的属性；类变更时立即清空旧属性、关闭旧编辑框。 */
const props = defineProps<{ item: OntClass }>(),
  emit = defineEmits<{
    edit: []
    remove: []
    command: [
      command: {
        label: string
        undo: (rev: number) => Promise<Space>
        redo: (rev: number) => Promise<Space>
      },
    ]
  }>()
const api = useSession().api,
  workspace = useWorkspace(),
  properties = useResource<OntProperty[]>([]),
  mutation = useMutation()
const focus = useEntityFocus(properties.data, properties.loading)
const dialog = ref(false),
  selected = ref<OntProperty | null>(null)
let propertySnapshot: OntProperty[] = []
let propertyEditing: OntProperty | null = null
const loadedRev = ref<number>()
async function load() {
  const { spaceId, id } = props.item
  let revision: number | undefined
  const result = await properties.load(async () => {
    const snapshot = await readDraftSnapshot(api, spaceId, () =>
      api.get<OntProperty[]>(`/properties?spaceId=${spaceId}&classId=${id}`),
    )
    revision = snapshot.space.draftRev
    return snapshot.items
  })
  // 局部读取只更新列表修订，不恢复因409撤销的整个工作区写权限。
  if (result && revision !== undefined) loadedRev.value = revision
}
watch(
  () => props.item.id,
  () => {
    dialog.value = false
    selected.value = null
    loadedRev.value = undefined
    void load()
  },
  { immediate: true },
)
function edit(item: OntProperty | null) {
  propertySnapshot = properties.data.value.map((entry) => ({ ...entry }))
  propertyEditing = item
  selected.value = item
  dialog.value = true
}
/** 属性新建/修改进入父级命令栈；删除暂不撤销。 */
async function propertySaved() {
  await load()
  const before = propertySnapshot
  propertySnapshot = []
  const spaceId = props.item.spaceId
  if (propertyEditing) {
    const old = before.find((entry) => entry.id === propertyEditing?.id)
    const after = properties.data.value.find((entry) => entry.id === propertyEditing?.id)
    if (!old || !after) return
    const put = (entry: OntProperty, rev: number) =>
      api.put<Space>(`/properties/${entry.id}`, {
        ...entry,
        spaceId,
        domainClassId: props.item.id,
        expectedDraftRev: rev,
      })
    emit('command', {
      label: `修改属性“${old.name}”`,
      undo: (rev) => put(old, rev),
      redo: (rev) => put(after, rev),
    })
    propertyEditing = null
    return
  }
  const beforeIds = new Set(before.map((entry) => entry.id))
  const created = properties.data.value.find((entry) => !beforeIds.has(entry.id))
  if (!created) return
  const payload = {
    name: created.name,
    rangeType: created.rangeType,
    required: created.required,
    description: created.description,
    facets: created.facets,
  }
  let createdId = created.id
  const findCreated = async () => {
    const rows = await api.get<OntProperty[]>(
      `/properties?spaceId=${spaceId}&classId=${props.item.id}`,
    )
    return rows.find((entry) => !beforeIds.has(entry.id) && entry.name === payload.name)?.id
  }
  emit('command', {
    label: `新建属性“${created.name}”`,
    undo: (rev) =>
      api.delete<Space>(`/properties/${createdId}?spaceId=${spaceId}&expectedDraftRev=${rev}`),
    redo: async (rev) => {
      const next = await api.post<Space>('/properties', {
        ...payload,
        spaceId,
        domainClassId: props.item.id,
        expectedDraftRev: rev,
      })
      const id = await findCreated()
      if (id) createdId = id
      return next
    },
  })
  propertyEditing = null
}
async function remove(item: OntProperty) {
  if (!workspace.canWrite || !workspace.space || loadedRev.value === undefined) return
  const space = workspace.space
  // 影响查询、刷新和人工确认之间列表修订仍可能变化；删除只能使用最初选中属性的写入基线。
  const expectedDraftRev = loadedRev.value
  await mutation.run(async ({ isCurrent }) => {
    const impact = await api.get<{ references: { kind: string; id: string; name: string }[] }>(
      `/properties/${item.id}/impact?spaceId=${space.id}`,
    )
    if (!isCurrent()) return
    if (impact.references.length)
      throw new Error(
        `此属性仍被引用，不能删除：${impact.references.map((entry) => `${entry.kind} · ${entry.name}`).join('，')}`,
      )
    if (!(await confirmAction(`删除属性“${item.name}”？此操作会更新草稿。`))) return
    if (!isCurrent()) return
    const next = await api.delete<Space>(
      `/properties/${item.id}?spaceId=${space.id}&expectedDraftRev=${expectedDraftRev}`,
    )
    if (!isCurrent()) return
    workspace.accept(next)
    await load()
  })
}
</script>
<template>
  <section class="panel property-panel">
    <div class="row spread">
      <div class="row">
        <h2>{{ item.name }}</h2>
        <el-tag v-if="item.deprecated" type="warning" size="small">已弃用</el-tag>
      </div>
      <div v-if="workspace.canWrite" class="row">
        <el-button link type="primary" @click="emit('edit')">编辑对象</el-button>
        <el-button link type="danger" @click="emit('remove')">删除对象</el-button>
      </div>
    </div>
    <p class="muted">{{ item.description || '暂无对象说明' }}</p>
    <div class="resource-id mono">{{ workspace.space?.namespaceUri }}class/{{ item.id }}</div>
    <div class="row spread property-heading">
      <h3>
        属性定义
        <span class="muted">{{ properties.data.value.length }}</span>
      </h3>
      <div class="row">
        <el-button text @click="load">刷新</el-button>
        <el-button v-if="workspace.canWrite" type="primary" plain @click="edit(null)">
          新建属性
        </el-button>
      </div>
    </div>
    <p v-if="mutation.error.value" class="error-text" role="alert">{{ mutation.error.value }}</p>
    <AsyncState
      :loading="properties.loading.value"
      :error="properties.error.value"
      :empty="!properties.data.value.length"
      empty-text="此对象尚未定义属性。可添加名称、类型和必填规则。"
      @retry="load"
    >
      <div class="overflow">
        <el-table :data="properties.data.value" row-key="id" :row-class-name="focus.rowClassName">
          <el-table-column prop="name" label="属性名称" min-width="120" />
          <el-table-column prop="rangeType" label="类型" width="105" />
          <el-table-column label="必填" width="65">
            <template #default="{ row }">{{ row.required ? '是' : '否' }}</template>
          </el-table-column>
          <el-table-column prop="description" label="说明" min-width="160" />
          <el-table-column label="高级分面" min-width="220">
            <template #default="{ row }">
              <code>{{ JSON.stringify(row.facets || {}) }}</code>
            </template>
          </el-table-column>
          <el-table-column v-if="workspace.canWrite" label="操作" width="115">
            <template #default="{ row }">
              <el-button link type="primary" @click="edit(row)">编辑</el-button>
              <el-button link type="danger" :disabled="mutation.pending.value" @click="remove(row)">
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </AsyncState>
    <PropertyDialog
      v-model="dialog"
      :item="selected"
      :class-id="item.id"
      :revision="loadedRev"
      @saved="propertySaved"
    />
  </section>
</template>
<style scoped>
.property-panel h2 {
  margin: 0;
}
.property-panel > p {
  margin: 14px 0;
}
.resource-id {
  font-size: 11px;
  background: var(--canvas);
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 10px;
  color: var(--muted);
}
.property-heading {
  border-top: 1px solid var(--line);
  margin-top: 24px;
  padding-top: 20px;
}
.property-heading h3 {
  margin: 0;
}
</style>
