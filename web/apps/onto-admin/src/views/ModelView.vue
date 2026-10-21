<script setup lang="ts">
/** 建模主工作区：对象树与属性面板按职责分离；异步重载保留有效选择，失败不制造本地成功状态。 */
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, reactive, ref, inject, watch } from 'vue'
import { routeLocationKey } from 'vue-router'
import type { Conflicts, Constraint, OntClass, Relation, Rule, Space } from '@onto/api-client'
import type Node from 'element-plus/es/components/tree/src/model/node'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useWorkspace } from '../stores/workspace'
import { useSession } from '../stores/session'
import { readDraftSnapshot } from '../composables/readDraftSnapshot'
import { useResource, errorMessage } from '../composables/useResource'
import { useMutation, confirmAction } from '../composables/useMutation'
import { buildTree, canReparent, type ClassNode } from '../composables/classTree'
import ClassDialog from '../components/ClassDialog.vue'
import PropertyPanel from '../components/PropertyPanel.vue'
const OntoGraph = defineAsyncComponent(() => import('@onto/graph/OntoGraph.vue'))
const workspace = useWorkspace(),
  api = useSession().api,
  classes = useResource<OntClass[]>([]),
  relations = useResource<Relation[]>([]),
  constraints = useResource<Constraint[]>([]),
  rules = useResource<Rule[]>([]),
  mutation = useMutation()
const route = inject(routeLocationKey, undefined)
const canvasHeight = ref(560)
function fitCanvas() {
  canvasHeight.value = Math.max(520, window.innerHeight - 300)
}
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
interface UndoAction {
  label: string
  undo: () => Promise<Space>
}
const undoStack = ref<UndoAction[]>([])
let classSnapshot: OntClass[] = []
let classEditing: OntClass | null = null
let relationSnapshot: Relation[] = []
const tree = computed(() => buildTree(classes.data.value)),
  selected = computed(() => classes.data.value.find((item) => item.id === selectedId.value))
const viewMode = ref<'graph' | 'tree'>('graph'),
  linkMode = ref(false),
  sideTab = ref<'properties' | 'relations' | 'constraints' | 'rules'>('properties'),
  draggingNew = ref(false),
  graphKey = ref(0),
  relationOpen = ref(false),
  conflictIds = ref<string[]>([]),
  conflictError = ref(''),
  relationForm = reactive({
    name: '',
    fromClassId: '',
    toClassId: '',
    cardinality: 'ONE_TO_MANY' as Relation['cardinality'],
    inverseName: '',
    transitive: false,
    symmetric: false,
    description: '',
  })
const graphNodes = computed(() =>
  classes.data.value.map((item) => ({
    id: item.id,
    label: item.name,
    conflict: conflictIds.value.includes(item.id),
  })),
)
const graphEdges = computed(() =>
  relations.data.value.map((item) => ({
    id: item.id,
    source: item.fromClassId,
    target: item.toClassId,
    label: item.name,
  })),
)
const selectedRelations = computed(() =>
  relations.data.value.filter(
    (item) => item.fromClassId === selectedId.value || item.toClassId === selectedId.value,
  ),
)
const selectedConstraints = computed(() =>
  constraints.data.value.filter(
    (item) => item.targetType === 'CLASS' && item.targetRef === selectedId.value,
  ),
)
const selectedRules = computed(() =>
  rules.data.value.filter((item) => item.applicableClassId === selectedId.value),
)
const classLabel = (id: string) =>
  classes.data.value.find((item) => item.id === id)?.name || id
function selectGraph(id: string) {
  selectedId.value = id
}
function dropNewObject() {
  if (!draggingNew.value) return
  draggingNew.value = false
  if (workspace.canWrite) edit(null)
}
function editGraph(id: string) {
  const item = classes.data.value.find((entry) => entry.id === id)
  if (item) edit(item)
}
function openRelation(payload: { source: string; target: string }) {
  if (!workspace.canWrite) return
  relationSnapshot = relations.data.value.map((entry) => ({ ...entry }))
  relationForm.name = ''
  relationForm.fromClassId = payload.source
  relationForm.toClassId = payload.target
  relationForm.cardinality = 'ONE_TO_MANY'
  mutation.error.value = ''
  relationOpen.value = true
}
async function saveRelation() {
  const space = workspace.space
  if (!space || !workspace.canWrite || loadedRev.value === undefined) return
  if (!relationForm.name.trim() || !relationForm.fromClassId || !relationForm.toClassId) {
    mutation.error.value = '请填写关系名称和两个端点。'
    return
  }
  if (relationForm.fromClassId === relationForm.toClassId) {
    mutation.error.value = '关系端点须为不同对象。'
    return
  }
  await mutation.run(async ({ isCurrent }) => {
    const next = await api.post<Space>('/relations', {
      ...relationForm,
      inverseName: relationForm.inverseName.trim() || undefined,
      spaceId: space.id,
      expectedDraftRev: loadedRev.value,
    })
    if (isCurrent()) workspace.accept(next)
  })
  if (!mutation.error.value) {
    relationOpen.value = false
    graphKey.value++
    await load()
    const spaceId = workspace.space?.id
    const beforeIds = new Set(relationSnapshot.map((item) => item.id))
    const created = relations.data.value.find((item) => !beforeIds.has(item.id))
    relationSnapshot = []
    if (spaceId && created)
      pushUndo(`新建关系“${created.name}”`, () =>
        api.delete<Space>(
          `/relations/${created.id}?spaceId=${spaceId}&expectedDraftRev=${workspace.space?.draftRev}`,
        ),
      )
  }
}
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
  await relations.load(() => api.get<Relation[]>(`/relations?spaceId=${id}`))
  await constraints.load(() => api.get<Constraint[]>(`/constraints?spaceId=${id}`))
  await rules.load(() => api.get<Rule[]>(`/rules?spaceId=${id}`))
  conflictError.value = ''
  try {
    const conflicts = await api.get<Conflicts>(`/spaces/${id}/conflicts`)
    conflictIds.value = [
      ...new Set(
        [...conflicts.errors, ...conflicts.warnings].flatMap((item) => item.entityIds),
      ),
    ]
  } catch (cause) {
    conflictIds.value = []
    conflictError.value = errorMessage(cause)
  }
  if (!classes.data.value.some((item) => item.id === selectedId.value)) selectedId.value = ''
}
function edit(item: OntClass | null) {
  if (!workspace.canWrite || loadedRev.value === undefined) return
  classSnapshot = classes.data.value.map((entry) => ({ ...entry }))
  classEditing = item
  editing.value = item
  dialog.value = true
}
function pushUndo(label: string, undo: () => Promise<Space>) {
  undoStack.value = [...undoStack.value.slice(-19), { label, undo }]
}
/** 撤销仅覆盖建模画布内的新建/修改/关系操作；删除与属性编辑仍需走草稿恢复。 */
async function undoLast() {
  const action = undoStack.value.at(-1)
  if (!action || mutation.pending.value || !workspace.canWrite) return
  undoStack.value = undoStack.value.slice(0, -1)
  await mutation.run(async ({ isCurrent }) => {
    const next = await action.undo()
    if (!isCurrent()) return
    workspace.accept(next)
    await load()
  })
  if (mutation.error.value) undoStack.value = [...undoStack.value, action]
}
async function classSaved() {
  await load()
  const before = classSnapshot
  classSnapshot = []
  const spaceId = workspace.space?.id
  if (!spaceId) return
  if (classEditing) {
    const old = before.find((item) => item.id === classEditing?.id)
    if (old)
      pushUndo(`修改对象“${old.name}”`, () =>
        api.put<Space>(`/classes/${old.id}`, {
          ...old,
          spaceId,
          expectedDraftRev: workspace.space?.draftRev,
        }),
      )
  } else {
    const beforeIds = new Set(before.map((item) => item.id))
    const created = classes.data.value.find((item) => !beforeIds.has(item.id))
    if (created)
      pushUndo(`新建对象“${created.name}”`, () =>
        api.delete<Space>(
          `/classes/${created.id}?spaceId=${spaceId}&expectedDraftRev=${workspace.space?.draftRev}`,
        ),
      )
  }
  classEditing = null
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
onMounted(() => {
  fitCanvas()
  window.addEventListener('resize', fitCanvas)
  void load()
})

onBeforeUnmount(() => window.removeEventListener('resize', fitCanvas))
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>建模工作台</h1>
      <p class="muted">画布编辑对象与关系；规则、冲突、策略、操作通过上方页签表格式维护。</p>
    </div>
    <div class="row">
      <el-button
        v-if="viewMode === 'graph' && workspace.canWrite"
        :type="linkMode ? 'primary' : 'default'"
        size="small"
        @click="linkMode = !linkMode"
      >
        {{ linkMode ? '退出连线' : '连线模式' }}
      </el-button>
      <el-radio-group v-model="viewMode" size="small">
        <el-radio-button value="graph">图谱</el-radio-button>
        <el-radio-button value="tree">层级</el-radio-button>
      </el-radio-group>
      <el-button
        :disabled="!undoStack.length || mutation.pending.value"
        title="撤销建模画布中的新建对象/关系操作；删除与属性编辑不可撤销"
        @click="undoLast"
      >
        撤销{{ undoStack.length ? ` ${undoStack.length}` : '' }}
      </el-button>
      <el-button @click="load">刷新模型</el-button>
      <el-button v-if="workspace.canWrite" type="primary" @click="edit(null)">新建对象</el-button>
    </div>
  </div>
  <p v-if="mutation.error.value" class="error-text" role="alert">{{ mutation.error.value }}</p>
  <p v-if="relations.error.value" class="error-text" role="alert">
    关系读取失败：{{ relations.error.value }}
    <el-button text @click="load">重试</el-button>
  </p>
  <p v-if="conflictError" class="error-text" role="alert">
    冲突检查读取失败：{{ conflictError }}
    <el-button text @click="load">重试</el-button>
  </p>
  <p v-if="focusMessage" class="notice" role="status">{{ focusMessage }}</p>
  <AsyncState :loading="classes.loading.value" :error="classes.error.value" @retry="load">
    <div class="model-grid" :class="{ 'graph-mode': viewMode === 'graph' }">
      <section v-if="viewMode === 'tree'" class="panel tree-panel">
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
      <section v-else class="panel graph-panel">
        <div class="row spread">
          <h2>对象关系图</h2>
          <span class="pill">
            {{ classes.data.value.length }} 对象 · {{ relations.data.value.length }} 关系
          </span>
        </div>
        <p v-if="conflictIds.length" class="notice" role="status">
          发现 {{ conflictIds.length }} 个对象涉及冲突，已在画布标红。
          <RouterLink :to="`/spaces/${workspace.space?.id}/conflicts`">查看冲突检查</RouterLink>
        </p>
        <div v-if="workspace.canWrite" class="row graph-tools">
          <span
            class="node-palette"
            draggable="true"
            @dragstart="draggingNew = true"
            @dragend="draggingNew = false"
          >
            ＋ 拖拽新建对象
          </span>
          <span class="muted">把此卡片拖到画布空白处，或双击画布空白处。</span>
        </div>
        <div
          class="graph-drop"
          :class="{ 'graph-drop-active': draggingNew }"
          @dragover.prevent
          @drop.prevent="dropNewObject"
        >
          <OntoGraph
            :key="graphKey"
            :nodes="graphNodes"
            :edges="graphEdges"
            :selected-id="selectedId"
            :editable="workspace.canWrite"
            :link-mode="linkMode"
            :height="canvasHeight"
            @select="selectGraph"
            @edit="editGraph"
            @create-edge="openRelation"
            @create-node="edit(null)"
          />
        </div>
      </section>
      <section v-if="selected" class="panel detail-panel">
        <el-tabs v-model="sideTab">
          <el-tab-pane label="属性" name="properties">
            <PropertyPanel
              :key="selected.id"
              :item="selected"
              @edit="edit(selected)"
              @remove="remove"
            />
          </el-tab-pane>
          <el-tab-pane :label="`关系 ${selectedRelations.length}`" name="relations">
            <p v-if="!selectedRelations.length" class="muted">该对象暂无关系。</p>
            <article v-for="item in selectedRelations" :key="item.id" class="record">
              <b>{{ item.name }}</b>
              <p class="muted">
                {{ classLabel(item.fromClassId) }} → {{ classLabel(item.toClassId) }} ·
                {{ item.cardinality }}
              </p>
            </article>
            <RouterLink :to="`/spaces/${workspace.space?.id}/relations`">
              去关系页维护
            </RouterLink>
          </el-tab-pane>
          <el-tab-pane :label="`约束 ${selectedConstraints.length}`" name="constraints">
            <p v-if="!selectedConstraints.length" class="muted">该对象暂无约束。</p>
            <article v-for="item in selectedConstraints" :key="item.id" class="record">
              <b>{{ item.name }}</b>
              <p class="muted">{{ item.kind }}</p>
            </article>
            <RouterLink :to="`/spaces/${workspace.space?.id}/constraints`">
              去约束页维护
            </RouterLink>
          </el-tab-pane>
          <el-tab-pane :label="`规则 ${selectedRules.length}`" name="rules">
            <p v-if="!selectedRules.length" class="muted">该对象暂无规则。</p>
            <article v-for="item in selectedRules" :key="item.id" class="record">
              <b>{{ item.name }}</b>
              <p class="muted">{{ item.kind }} · {{ item.severity }}</p>
            </article>
            <RouterLink :to="`/spaces/${workspace.space?.id}/rules`">去规则页维护</RouterLink>
          </el-tab-pane>
        </el-tabs>
      </section>
      <section v-else class="panel model-placeholder">
        <div class="ontology-glyph">◈</div>
        <h2>选择一个领域对象</h2>
        <p class="muted">
          {{
            viewMode === 'graph'
              ? '画布展示对象与关系，右侧展示对象说明与属性。'
              : '左侧展示对象层级，右侧展示对象说明与属性。'
          }}
          <br />
          对象使用稳定 URI 标识，名称可随草稿修订。
        </p>
      </section>
    </div>
  </AsyncState>
  <el-dialog v-model="relationOpen" title="新建关系" width="520px" @closed="graphKey++">
    <el-form label-position="top" @submit.prevent="saveRelation">
      <el-form-item label="关系名称" required>
        <el-input v-model="relationForm.name" placeholder="例如：使用算法" />
      </el-form-item>
      <el-form-item label="方向">
        <span>{{ classLabel(relationForm.fromClassId) }} → {{ classLabel(relationForm.toClassId) }}</span>
      </el-form-item>
      <el-form-item label="数量约束">
        <el-select v-model="relationForm.cardinality">
          <el-option value="ONE_TO_ONE" label="一对一" />
          <el-option value="ONE_TO_MANY" label="一对多" />
          <el-option value="MANY_TO_ONE" label="多对一" />
          <el-option value="MANY_TO_MANY" label="多对多" />
        </el-select>
      </el-form-item>
      <p class="muted">逆关系可在“关系与逆关系”页继续维护；保存后画布立即刷新。</p>
    </el-form>
    <template #footer>
      <el-button @click="relationOpen = false">取消</el-button>
      <el-button type="primary" :loading="mutation.pending.value" @click="saveRelation">
        保存关系
      </el-button>
    </template>
  </el-dialog>
  <ClassDialog
    v-model="dialog"
    :item="editing"
    :classes="classes.data.value"
    :revision="loadedRev"
    @saved="classSaved"
  />
</template>
<style scoped>
.node-palette {
  display: inline-block;
  padding: 6px 12px;
  border: 1px dashed #2563eb;
  border-radius: 6px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 13px;
  cursor: grab;
  user-select: none;
}
.graph-drop {
  border-radius: 8px;
  outline: 2px dashed transparent;
  transition: outline-color 0.15s;
}
.graph-drop-active {
  outline-color: #2563eb;
}
.detail-panel {
  min-height: 0;
  max-height: calc(100vh - 220px);
  overflow: auto;
  padding: 8px 14px;
}
.detail-panel :deep(.panel) {
  border: 0;
  padding: 0;
  background: transparent;
  box-shadow: none;
}
.model-grid {
  display: grid;
  grid-template-columns: minmax(240px, 300px) minmax(0, 1fr);
  gap: 12px;
  align-items: stretch;
}
.model-grid.graph-mode {
  grid-template-columns: minmax(0, 1fr) minmax(280px, 360px);
}
.graph-panel {
  padding: 10px 12px;
  min-height: 0;
}
.graph-panel h2 {
  margin: 0;
  font-size: 16px;
}
.graph-tools {
  margin: 4px 0 8px;
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
