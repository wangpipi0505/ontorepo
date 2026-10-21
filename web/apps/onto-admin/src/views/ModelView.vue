<script setup lang="ts">
/** 建模主工作区：对象树与属性面板按职责分离；异步重载保留有效选择，失败不制造本地成功状态。 */
import { computed, onMounted, ref, inject, watch } from 'vue'
import { routeLocationKey } from 'vue-router'
import type { OntClass, Space } from '@onto/api-client'
import type Node from 'element-plus/es/components/tree/src/model/node'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useWorkspace } from '../stores/workspace'
import { useSession } from '../stores/session'
import { readDraftSnapshot } from '../composables/readDraftSnapshot'
import { useResource } from '../composables/useResource'
import { useMutation, confirmAction } from '../composables/useMutation'
import { buildTree, canReparent, type ClassNode } from '../composables/classTree'
import ClassDialog from '../components/ClassDialog.vue'
import PropertyPanel from '../components/PropertyPanel.vue'
const workspace = useWorkspace(),
  api = useSession().api,
  classes = useResource<OntClass[]>([]),
  mutation = useMutation()
const route = inject(routeLocationKey, undefined)
const focusId = computed(() =>
  typeof route?.query.classId === 'string'
    ? route.query.classId
    : typeof route?.query.entityId === 'string'
      ? route.query.entityId
      : '',
)
const focusMessage = computed(() =>
  !focusId.value || classes.loading.value
    ? ''
    : classes.data.value.some((item) => item.id === focusId.value)
      ? '已定位关联对象，可在下方查看对象及属性。'
      : '关联对象已删除或已变更，请返回冲突检查重新检查当前草稿。',
)
watch([focusId, classes.data], () => {
  if (classes.data.value.some((item) => item.id === focusId.value)) selectedId.value = focusId.value
})
const selectedId = ref(''),
  dialog = ref(false),
  editing = ref<OntClass | null>(null),
  loadedRev = ref<number>()
const tree = computed(() => buildTree(classes.data.value)),
  selected = computed(() => classes.data.value.find((item) => item.id === selectedId.value))
async function load() {
  const id = workspace.space?.id
  if (!id) return
  if (workspace.stale) dialog.value = false
  let latestSpace: Space | undefined
  const result = await classes.load(async () => {
    const snapshot = await readDraftSnapshot(api, id, () =>
      api.get<OntClass[]>(`/classes?spaceId=${id}`),
    )
    latestSpace = snapshot.space
    return snapshot.items
  })
  // 完整树和修订均已取回，才恢复写权限；旧请求的结果不会由资源版本校验返回。
  if (result && latestSpace) {
    loadedRev.value = latestSpace.draftRev
    workspace.refreshed(latestSpace)
  }
  if (!classes.data.value.some((item) => item.id === selectedId.value)) selectedId.value = ''
}
function edit(item: OntClass | null) {
  if (!workspace.canWrite || loadedRev.value === undefined) return
  editing.value = item
  dialog.value = true
}
async function remove() {
  const item = selected.value,
    space = workspace.space
  if (!item || !space || !workspace.canWrite || loadedRev.value === undefined) return
  const expectedDraftRev = loadedRev.value,
    context = workspace.captureContext()
  if (await confirmAction(`删除对象“${item.name}”？存在子对象或属性时服务端会拒绝删除。`)) {
    if (!workspace.isCurrentContext(context)) return
    await mutation.run(async ({ isCurrent }) => {
      const next = await api.delete<Space>(
        `/classes/${item.id}?spaceId=${space.id}&expectedDraftRev=${expectedDraftRev}`,
      )
      if (!isCurrent()) return
      workspace.accept(next)
      await load()
    })
  }
}
function allowDrop(drag: Node, target: Node, type: string) {
  return (
    workspace.canWrite &&
    loadedRev.value !== undefined &&
    !mutation.pending.value &&
    type === 'inner' &&
    canReparent(classes.data.value, String(drag.data.id), String(target.data.id))
  )
}
/** 树拖动只是父级更新手势。无排序语义；失败后重读，不能留下一棵虚假成功的树。 */
async function dropped(drag: Node, target: Node) {
  const item = classes.data.value.find((entry) => entry.id === drag.data.id),
    space = workspace.space
  if (!item || !space || !workspace.canWrite || loadedRev.value === undefined) return
  const expectedDraftRev = loadedRev.value
  await mutation.run(async ({ isCurrent }) => {
    const next = await api.put<Space>(`/classes/${item.id}`, {
      ...item,
      parentId: String(target.data.id),
      expectedDraftRev,
    })
    if (!isCurrent()) return
    workspace.accept(next)
    await load()
  })
  if (mutation.error.value) await load()
}
function select(node: ClassNode) {
  selectedId.value = node.id
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>对象与属性</h1>
      <p class="muted">用层级组织领域对象，在右侧维护属性定义。</p>
    </div>
    <div class="row">
      <el-button @click="load">刷新模型</el-button>
      <el-button v-if="workspace.canWrite" type="primary" @click="edit(null)">新建对象</el-button>
    </div>
  </div>
  <p v-if="mutation.error.value" class="error-text" role="alert">{{ mutation.error.value }}</p>
  <p v-if="focusMessage" class="notice" role="status">{{ focusMessage }}</p>
  <AsyncState :loading="classes.loading.value" :error="classes.error.value" @retry="load">
    <div class="model-grid">
      <section class="panel tree-panel">
        <div class="row spread">
          <h2>对象层级</h2>
          <span class="pill">{{ classes.data.value.length }} 对象</span>
        </div>
        <p class="muted tree-help">
          {{
            workspace.canWrite
              ? '拖入对象以调整父级，也可在编辑中选择。'
              : '选择对象查看属性与说明。'
          }}
        </p>
        <el-tree
          v-if="tree.length"
          :data="tree"
          node-key="id"
          :props="{ label: 'name', children: 'children' }"
          default-expand-all
          highlight-current
          :current-node-key="selectedId"
          :draggable="workspace.canWrite && !mutation.pending.value"
          :allow-drop="allowDrop"
          :expand-on-click-node="false"
          @node-click="select"
          @node-drop="dropped"
        >
          <template #default="{ data }">
            <span class="tree-item">
              <span class="tree-dot" />
              {{ data.name }}
              <small v-if="data.deprecated">弃用</small>
            </span>
          </template>
        </el-tree>
        <div v-else class="state-block">
          还没有对象
          <br />
          <span class="muted">新建第一个对象以开始建模。</span>
        </div>
      </section>
      <PropertyPanel
        v-if="selected"
        :key="selected.id"
        :item="selected"
        @edit="edit(selected)"
        @remove="remove"
      />
      <section v-else class="panel model-placeholder">
        <div class="ontology-glyph">◈</div>
        <h2>选择一个领域对象</h2>
        <p class="muted">
          左侧展示对象层级，右侧展示对象说明与属性。
          <br />
          对象使用稳定 URI 标识，名称可随草稿修订。
        </p>
      </section>
    </div>
  </AsyncState>
  <ClassDialog
    v-model="dialog"
    :item="editing"
    :classes="classes.data.value"
    :revision="loadedRev"
    @saved="load"
  />
</template>
<style scoped>
.model-grid {
  display: grid;
  grid-template-columns: minmax(240px, 300px) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}
.tree-panel {
  padding: 20px 14px;
  min-height: 480px;
}
.tree-panel h2 {
  margin: 0;
}
.tree-help {
  font-size: 11px;
  padding: 14px 0;
  margin: 0;
}
.tree-item {
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.tree-dot {
  width: 7px;
  height: 7px;
  border: 2px solid #4d7bd4;
  border-radius: 2px;
}
.tree-item small {
  font-size: 10px;
  color: #a6731e;
}
.tree-panel :deep(.el-tree-node__content) {
  height: 36px;
  border-radius: 4px;
}
.model-placeholder {
  min-height: 480px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  text-align: center;
}
.ontology-glyph {
  font-size: 60px;
  line-height: 1;
  color: #b6cae9;
  margin-bottom: 24px;
}
.model-placeholder h2 {
  font-size: 18px;
}
.model-placeholder p {
  font-size: 12px;
}
@media (max-width: 1000px) {
  .model-grid {
    grid-template-columns: 230px minmax(0, 1fr);
  }
}
@media (max-width: 780px) {
  .model-grid {
    grid-template-columns: 1fr;
  }
  .tree-panel {
    min-height: 180px;
    max-height: 360px;
    overflow: auto;
  }
  .model-placeholder {
    min-height: 260px;
  }
}
</style>
