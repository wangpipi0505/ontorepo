<script setup lang="ts">
/** 来源/快照代次和空间上下文同时约束每次预填，旧拖拽或迟到快照不能跨上下文复用。 */
import { computed, ref, watch } from 'vue'
import type {
  Datasource,
  MappingCollection,
  MetadataSnapshot,
  MetadataSummary,
  OntClass,
  OntProperty,
  Relation,
} from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import { useResource } from '../../composables/useResource'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import MappingCanvas from './MappingCanvas.vue'
import {
  mappingEdges,
  prefillMapping,
  recommendMappings,
  type GraphContext,
  type MappingEdge,
  type MappingPrefill,
  type MappingRecommendation,
  type SourcePick,
  type TargetPick,
} from './mappingGraph'
const props = defineProps<{
  sources: Datasource[]
  mappings: MappingCollection
  classes: OntClass[]
  properties: OntProperty[]
  relations: Relation[]
  canWrite: boolean
  disabled: boolean
}>()
const emit = defineEmits<{ prefill: [value: MappingPrefill]; edit: [value: MappingEdge] }>()
const workspace = useWorkspace(),
  api = useSession().api
const sourceId = ref(''),
  snapshotId = ref('')
const summaries = useResource<MetadataSummary[]>([]),
  snapshot = useResource<MetadataSnapshot | null>(null)
const selected = ref<SourcePick | null>(null),
  drag = ref<SourcePick | null>(null)
const selectedMapping = ref(''),
  message = ref(''),
  recommendations = ref<MappingRecommendation[] | null>(null)
const origin = workspace.captureContext()
const activeSpace = computed(() =>
  workspace.isCurrentContext(origin) ? workspace.space?.id : undefined,
)
const writable = computed(
  () => props.canWrite && workspace.canWrite && !!activeSpace.value && !props.disabled,
)
const context = computed<GraphContext | null>(() =>
  snapshot.data.value && sourceId.value && activeSpace.value
    ? {
        sourceId: sourceId.value,
        snapshot: snapshot.data.value,
        mappings: props.mappings,
        classes: props.classes,
        properties: props.properties,
      }
    : null,
)
const edges = computed(() => (context.value ? mappingEdges(context.value, props.relations) : []))
const located = computed(() => edges.value.find((edge) => edge.id === selectedMapping.value))
function clearGesture() {
  selected.value = null
  drag.value = null
  selectedMapping.value = ''
  message.value = ''
  recommendations.value = null
}
async function loadSummaries() {
  const source = sourceId.value,
    spaceId = activeSpace.value
  if (source && spaceId && workspace.isCurrentContext(origin))
    await summaries.load(() =>
      api.get<MetadataSummary[]>(`/datasources/${source}/snapshots?spaceId=${spaceId}`),
    )
}
async function loadSnapshot() {
  const source = sourceId.value,
    id = snapshotId.value,
    spaceId = activeSpace.value
  // 快照只能来自当前源返回的列表；不接受外部拖拽载荷中的源或快照标识。
  if (
    source &&
    id &&
    spaceId &&
    workspace.isCurrentContext(origin) &&
    summaries.data.value.some((item) => item.id === id)
  )
    await snapshot.load(() => apiSnapshot(source, id, spaceId))
}
async function apiSnapshot(source: string, id: string, spaceId: string) {
  const result = await api.get<MetadataSnapshot>(
    `/datasources/${source}/snapshots/${id}?spaceId=${spaceId}`,
  )
  if (result.id !== id) throw new Error('返回的快照与所选快照不一致，请重新读取。')
  return result
}
watch(
  sourceId,
  () => {
    snapshotId.value = ''
    summaries.reset()
    snapshot.reset()
    clearGesture()
    void loadSummaries()
  },
  { flush: 'sync' },
)
watch(
  snapshotId,
  () => {
    snapshot.reset()
    clearGesture()
    void loadSnapshot()
  },
  { flush: 'sync' },
)
watch(
  () => workspace.space?.id,
  () => {
    sourceId.value = ''
    summaries.reset()
    snapshot.reset()
    clearGesture()
  },
  { flush: 'sync' },
)
watch(
  () => props.disabled,
  (value) => {
    if (value) {
      selected.value = null
      drag.value = null
    }
  },
)
watch(
  () => props.mappings,
  () => {
    recommendations.value = null
    selected.value = null
    drag.value = null
  },
)
function chooseSource(value: SourcePick) {
  if (!props.disabled) {
    selected.value = value
    message.value = ''
    selectedMapping.value = ''
  }
}
function connect(target: TargetPick, source = selected.value) {
  // 同空间重新进入先递增非响应式代次；事件入口必须即时核验，不能只信 computed 缓存。
  if (!workspace.isCurrentContext(origin) || !writable.value || !context.value || !source) return
  message.value = ''
  try {
    const value = prefillMapping(context.value, source, target)
    emit('prefill', value)
    selected.value = null
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '请检查映射端点。'
  }
}
function startDrag(value: SourcePick, event: DragEvent) {
  if (!workspace.isCurrentContext(origin) || !writable.value) {
    event.preventDefault()
    return
  }
  chooseSource(value)
  drag.value = value
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'link'
    event.dataTransfer.setData('text/plain', value.field ?? value.resource)
  }
}
function drop(target: TargetPick) {
  if (drag.value) connect(target, drag.value)
  drag.value = null
}
function locate(edge: MappingEdge) {
  if (!props.disabled) {
    selectedMapping.value = edge.id
    selected.value = null
    message.value = ''
  }
}
function recommend() {
  if (writable.value && context.value) recommendations.value = recommendMappings(context.value)
}
</script>
<template>
  <section class="panel stack graph-workbench">
    <div class="row spread">
      <h2>图形映射</h2>
      <span class="muted">来源树 ↔ 本体树</span>
    </div>
    <p class="muted">
      拖动来源到目标，或先点来源，再点目标（Tab / Enter
      同样可用）。连线只预填表单，确认保存后才成为实线。窄屏可横向滚动双树。
    </p>
    <div class="graph-selectors">
      <label>
        数据源
        <select v-model="sourceId" aria-label="图形映射数据源" :disabled="disabled || !activeSpace">
          <option value="">选择数据源</option>
          <option v-for="item in sources" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
      </label>
      <label>
        元数据快照
        <select
          v-model="snapshotId"
          aria-label="图形映射快照"
          :disabled="disabled || summaries.loading.value || !sourceId"
        >
          <option value="">选择已保存快照</option>
          <option v-for="item in summaries.data.value" :key="item.id" :value="item.id">
            {{ item.capturedAt }} · {{ item.sha256 }}
          </option>
        </select>
      </label>
    </div>
    <AsyncState
      :loading="summaries.loading.value"
      :error="summaries.error.value"
      @retry="loadSummaries"
    >
      <p v-if="sourceId && !summaries.data.value.length" class="muted">
        该源没有快照，请先在数据源管理中同步元数据。
      </p>
    </AsyncState>
    <AsyncState
      :loading="snapshot.loading.value"
      :error="snapshot.error.value"
      @retry="loadSnapshot"
    >
      <template v-if="context">
        <p class="snapshot-proof muted">
          快照 {{ context.snapshot.id }} · 摘要 {{ context.snapshot.sha256 }}
        </p>
        <MappingCanvas
          :source-id="sourceId"
          :snapshot="context.snapshot"
          :mappings="mappings"
          :classes="classes"
          :properties="properties"
          :relations="relations"
          :edges="edges"
          :selected="selected"
          :selected-mapping="selectedMapping"
          :writable="writable"
          :disabled="disabled"
          :dragging="!!drag"
          @source="chooseSource"
          @target="connect"
          @drag="startDrag"
          @drop="drop"
          @dragend="drag = null"
          @locate="locate"
        />
        <p v-if="selected" class="selection-note" role="status">
          已选来源：{{ selected.resource }}{{ selected.field ? ` / ${selected.field}` : '' }}。{{
            writable ? '请选择本体目标。' : '当前权限只可查看已有映射。'
          }}
          <el-button link @click="selected = null">取消选择</el-button>
        </p>
        <p v-if="message" class="error-text" role="alert">{{ message }}</p>
        <div class="saved-mappings">
          <h3>已保存映射 · {{ edges.length }}</h3>
          <p v-if="!edges.length" class="muted">当前来源和快照尚无映射。</p>
          <button
            v-for="edge in edges"
            :key="edge.id"
            type="button"
            :data-mapping-id="edge.id"
            :disabled="disabled"
            :class="{ active: edge.id === selectedMapping }"
            @click="locate(edge)"
          >
            {{ edge.label }}
          </button>
        </div>
        <div v-if="located" class="located-mapping">
          <strong>已定位 {{ located.id }}</strong>
          <p>{{ located.label }}</p>
          <p v-if="'join' in located.item">
            连接键：{{ located.item.join.fromField }} ↔ {{ located.item.join.toField }}；起点模板
            {{ located.item.fromKeyExpr }}；终点模板 {{ located.item.toKeyExpr }}
          </p>
          <el-button v-if="canWrite" :disabled="!writable" @click="emit('edit', located)">
            影响 / 编辑此映射
          </el-button>
        </div>
        <div class="recommendations">
          <el-button v-if="canWrite" :disabled="!writable" @click="recommend">
            生成映射推荐
          </el-button>
          <p class="muted">
            推荐分数来自确定性名称与类型匹配规则，不是概率。每项需人工审查保存；需类型转换的字段请使用下方完整表单。
          </p>
          <template v-if="recommendations">
            <p v-if="!recommendations.length">没有符合名称和类型规则的新推荐。</p>
            <article v-for="item in recommendations" :key="item.id" class="recommendation">
              <strong>{{ item.label }} · {{ item.confidence }}%</strong>
              <p class="muted">{{ item.evidence }}</p>
              <el-button
                :disabled="!writable"
                data-review-recommendation
                @click="connect(item.target, item.source)"
              >
                审查此推荐
              </el-button>
            </article>
          </template>
        </div>
      </template>
      <p v-else-if="!snapshot.error.value" class="muted">选择数据源及真实快照后显示可连线节点。</p>
    </AsyncState>
  </section>
</template>
<style scoped>
.graph-workbench {
  min-width: 0;
}
.graph-selectors {
  display: grid;
  grid-template-columns: minmax(150px, 1fr) minmax(200px, 2fr);
  gap: 16px;
}
.graph-selectors label {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 13px;
  min-width: 0;
}
.graph-selectors select {
  width: 100%;
  min-width: 0;
  padding: 9px;
  border: 1px solid var(--line);
  border-radius: 4px;
  color: var(--text);
  background: white;
}
.snapshot-proof,
.recommendation p,
.located-mapping {
  overflow-wrap: anywhere;
}
.selection-note,
.located-mapping {
  padding: 12px;
  background: #eef4fc;
  border-radius: 4px;
}
.saved-mappings {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.saved-mappings h3 {
  flex-basis: 100%;
  font-size: 14px;
}
.saved-mappings button {
  max-width: 100%;
  border: 1px solid var(--line);
  color: var(--text);
  border-radius: 4px;
  padding: 8px;
  background: white;
  cursor: pointer;
  overflow-wrap: anywhere;
  text-align: left;
}
.saved-mappings button.active {
  border-color: var(--primary);
  background: #eef4fc;
}
.recommendations {
  margin-top: 18px;
  border-top: 1px solid var(--line);
  padding-top: 16px;
}
.recommendation {
  padding: 12px 0;
  border-bottom: 1px solid var(--line);
}
@media (max-width: 600px) {
  .graph-selectors {
    grid-template-columns: 1fr;
  }
}
</style>
