<script setup lang="ts">
/** SVG 只负责可视连接，坐标取真实 DOM 几何；滚动、缩放、内容变化后重新测量。 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type {
  MappingCollection,
  MetadataSnapshot,
  OntClass,
  OntProperty,
  Relation,
} from '@onto/api-client'
import {
  classRows,
  fieldNode,
  relationNode,
  resourceNode,
  selectedNode,
  targetNode,
  type MappingEdge,
  type SourcePick,
  type TargetPick,
} from './mappingGraph'
const props = defineProps<{
  sourceId: string
  snapshot: MetadataSnapshot
  mappings: MappingCollection
  classes: OntClass[]
  properties: OntProperty[]
  relations: Relation[]
  edges: MappingEdge[]
  selected: SourcePick | null
  selectedMapping: string
  writable: boolean
  disabled: boolean
  dragging: boolean
}>()
const emit = defineEmits<{
  source: [value: SourcePick]
  target: [value: TargetPick]
  drag: [value: SourcePick, event: DragEvent]
  drop: [value: TargetPick]
  dragend: []
  locate: [value: MappingEdge]
}>()
const sheet = ref<HTMLElement | null>(null),
  zoom = ref(1)
const paths = ref<{ edge: MappingEdge; path: string }[]>([])
const draftPath = ref(''),
  pointer = ref<{ x: number; y: number } | null>(null)
const rows = computed(() => classRows(props.classes))
const objects = computed(() =>
  props.mappings.objects.filter(
    (item) => item.datasourceId === props.sourceId && item.metaSnapshotId === props.snapshot.id,
  ),
)
const mappedRelations = computed(() =>
  props.mappings.relations.filter(
    (item) => item.datasourceId === props.sourceId && item.metaSnapshotId === props.snapshot.id,
  ),
)
let observer: ResizeObserver | undefined, frame: number | undefined
function curve(x1: number, y1: number, x2: number, y2: number) {
  const middle = (x1 + x2) / 2
  return `M ${x1} ${y1} C ${middle} ${y1}, ${middle} ${y2}, ${x2} ${y2}`
}
function measure() {
  const root = sheet.value
  if (!root) return
  const bounds = root.getBoundingClientRect(),
    scale = bounds.width && root.offsetWidth ? bounds.width / root.offsetWidth : zoom.value
  const nodes = new Map(
    Array.from(root.querySelectorAll<HTMLElement>('[data-graph-node]'), (node) => [
      node.dataset.graphNode!,
      node.getBoundingClientRect(),
    ]),
  )
  paths.value = props.edges.flatMap((edge) => {
    const from = nodes.get(edge.from),
      to = nodes.get(edge.to)
    if (!from || !to) return []
    return [
      {
        edge,
        path: curve(
          (from.right - bounds.left) / scale,
          (from.top + from.height / 2 - bounds.top) / scale,
          (to.left - bounds.left) / scale,
          (to.top + to.height / 2 - bounds.top) / scale,
        ),
      },
    ]
  })
  const start = props.selected && nodes.get(selectedNode(props.selected))
  draftPath.value =
    props.dragging && pointer.value && start
      ? curve(
          (start.right - bounds.left) / scale,
          (start.top + start.height / 2 - bounds.top) / scale,
          (pointer.value.x - bounds.left) / scale,
          (pointer.value.y - bounds.top) / scale,
        )
      : ''
}
function schedule() {
  if (frame !== undefined) cancelAnimationFrame(frame)
  frame = requestAnimationFrame(() => {
    frame = undefined
    measure()
  })
}
function dragOver(event: DragEvent) {
  event.preventDefault()
  pointer.value = { x: event.clientX, y: event.clientY }
  schedule()
}
function isSelected(pick: SourcePick) {
  return !!props.selected && selectedNode(props.selected) === selectedNode(pick)
}
watch(
  () => [
    props.edges,
    props.snapshot,
    props.selectedMapping,
    props.selected,
    props.dragging,
    zoom.value,
  ],
  () => {
    void nextTick(schedule)
  },
)
watch(
  () => props.selectedMapping,
  async (id) => {
    await nextTick()
    const edge = props.edges.find((item) => item.id === id)
    if (!edge) return
    // 仅滚动画布内部，避免定位一个映射把表单或页面导航滚出视野。
    const node = Array.from(
      sheet.value?.querySelectorAll<HTMLElement>('[data-graph-node]') ?? [],
    ).find((item) => item.dataset.graphNode === edge.from)
    const viewport = sheet.value?.parentElement
    if (node && viewport) viewport.scrollTop = Math.max(0, node.offsetTop * zoom.value - 40)
    schedule()
  },
)
onMounted(() => {
  if (typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(schedule)
    if (sheet.value) observer.observe(sheet.value)
  }
  window.addEventListener('resize', schedule)
  schedule()
})
onBeforeUnmount(() => {
  observer?.disconnect()
  if (frame !== undefined) cancelAnimationFrame(frame)
  window.removeEventListener('resize', schedule)
})
</script>
<template>
  <div class="row graph-tools">
    <label>
      图形缩放
      <select v-model.number="zoom" aria-label="映射图缩放">
        <option :value="0.75">75%</option>
        <option :value="1">100%</option>
        <option :value="1.25">125%</option>
      </select>
    </label>
    <span class="muted">实线为已保存映射 · 拖拽虚线为待审查连接</span>
  </div>
  <div
    class="mapping-viewport"
    tabindex="0"
    aria-label="映射双树，可横向和纵向滚动"
    @scroll="schedule"
  >
    <div
      ref="sheet"
      class="mapping-sheet"
      :style="{ zoom }"
      @dragover="dragOver"
      @dragend="emit('dragend')"
      @drop="emit('dragend')"
    >
      <svg class="mapping-lines" aria-label="已保存映射连线">
        <g v-for="{ edge, path } in paths" :key="`${edge.kind}-${edge.id}`">
          <path
            :d="path"
            class="edge-hit"
            tabindex="0"
            role="button"
            :aria-label="`定位${edge.label}`"
            @click="emit('locate', edge)"
            @keydown.enter.prevent="emit('locate', edge)"
            @keydown.space.prevent="emit('locate', edge)"
          />
          <path
            :d="path"
            class="edge-line"
            :class="{ chosen: selectedMapping === edge.id, relation: edge.kind === 'relations' }"
          >
            <title>{{ edge.label }}</title>
          </path>
        </g>
        <path v-if="draftPath" :d="draftPath" class="draft-line" />
      </svg>
      <div class="tree-column source-tree">
        <h3>来源资源与字段</h3>
        <div v-for="resource in snapshot.resources" :key="resource.name" class="tree-group">
          <button
            type="button"
            class="graph-node resource-node"
            :class="{ selected: isSelected({ resource: resource.name }) }"
            :data-graph-node="resourceNode(resource.name)"
            :data-source-resource="resource.name"
            :draggable="writable && !disabled"
            :disabled="disabled"
            @click="emit('source', { resource: resource.name })"
            @dragstart="emit('drag', { resource: resource.name }, $event)"
          >
            {{ resource.name }}
            <small>
              {{ resource.fields.length }} 个字段 · 主键
              {{ resource.primaryKey.join(' + ') || '未声明' }}
            </small>
          </button>
          <div
            v-for="object in objects.filter((item) => item.sourceResource === resource.name)"
            :key="object.id"
            class="field-group"
          >
            <p class="muted">
              所属对象：{{
                classes.find((item) => item.id === object.targetClassId)?.name ||
                object.targetClassId
              }}
              <small>{{ object.id }}</small>
            </p>
            <button
              v-for="field in resource.fields"
              :key="field.name"
              type="button"
              class="graph-node field-node"
              :class="{
                selected: isSelected({
                  resource: resource.name,
                  objectMappingId: object.id,
                  field: field.name,
                }),
              }"
              :data-graph-node="fieldNode(object.id, field.name)"
              :data-source-field="`${object.id}:${field.name}`"
              :draggable="writable && !disabled"
              :disabled="disabled"
              @click="
                emit('source', {
                  resource: resource.name,
                  objectMappingId: object.id,
                  field: field.name,
                })
              "
              @dragstart="
                emit(
                  'drag',
                  { resource: resource.name, objectMappingId: object.id, field: field.name },
                  $event,
                )
              "
            >
              {{ field.name }}
              <small>{{ field.type }} · {{ field.nullable ? '可空' : '非空' }}</small>
            </button>
          </div>
          <div
            v-if="!objects.some((item) => item.sourceResource === resource.name)"
            class="field-group"
          >
            <p class="muted">先保存资源到类的对象映射，再连接字段。</p>
            <div v-for="field in resource.fields" :key="field.name" class="unmapped-field">
              {{ field.name }}
              <small>{{ field.type }}</small>
            </div>
          </div>
        </div>
        <h3 v-if="mappedRelations.length">已有关系连接键</h3>
        <button
          v-for="item in mappedRelations"
          :key="item.id"
          type="button"
          class="graph-node relation-node"
          :data-graph-node="relationNode('source', item.id)"
          :disabled="disabled"
          @click="
            edges.find((edge) => edge.id === item.id) &&
            emit('locate', edges.find((edge) => edge.id === item.id)!)
          "
        >
          {{ item.join.fromField }} ↔ {{ item.join.toField }}
          <small>{{ item.fromKeyExpr }} → {{ item.toKeyExpr }}</small>
        </button>
        <p v-if="!snapshot.resources.length" class="muted">当前快照没有可见资源。</p>
      </div>
      <div class="tree-column target-tree">
        <h3>本体概念与属性</h3>
        <div
          v-for="{ value: cls, depth } in rows"
          :key="cls.id"
          class="tree-group"
          :style="{ paddingLeft: `${Math.min(depth, 4) * 12}px` }"
        >
          <button
            type="button"
            class="graph-node class-node"
            :data-graph-node="targetNode('class', cls.id)"
            :data-target-class="cls.id"
            :disabled="disabled"
            @click="emit('target', { kind: 'class', id: cls.id })"
            @dragover.prevent
            @drop.stop.prevent="emit('drop', { kind: 'class', id: cls.id })"
          >
            {{ cls.name }}
            <small>
              {{
                cls.parentId
                  ? `父类：${classes.find((item) => item.id === cls.parentId)?.name || cls.parentId}`
                  : '根对象'
              }}
            </small>
          </button>
          <button
            v-for="property in properties.filter((item) => item.domainClassId === cls.id)"
            :key="property.id"
            type="button"
            class="graph-node property-node"
            :data-graph-node="targetNode('property', property.id)"
            :data-target-property="property.id"
            :disabled="disabled"
            @click="emit('target', { kind: 'property', id: property.id })"
            @dragover.prevent
            @drop.stop.prevent="emit('drop', { kind: 'property', id: property.id })"
          >
            {{ property.name }}
            <small>{{ property.rangeType }}{{ property.required ? ' · 必填' : '' }}</small>
          </button>
        </div>
        <h3 v-if="mappedRelations.length">已映射本体关系</h3>
        <button
          v-for="relation in relations.filter((item) =>
            mappedRelations.some((mapping) => mapping.relationId === item.id),
          )"
          :key="relation.id"
          type="button"
          class="graph-node relation-node"
          :data-graph-node="relationNode('target', relation.id)"
          :disabled="disabled"
          @click="
            edges.find((edge) => edge.to === relationNode('target', relation.id)) &&
            emit('locate', edges.find((edge) => edge.to === relationNode('target', relation.id))!)
          "
        >
          {{ relation.name }}
          <small>{{ relation.cardinality }}</small>
        </button>
        <p v-if="!classes.length" class="muted">请先在对象与属性页面建立本体概念。</p>
      </div>
    </div>
  </div>
</template>
<style scoped>
.graph-tools {
  flex-wrap: wrap;
  font-size: 12px;
}
.mapping-viewport {
  max-height: 580px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #f6f8fb;
}
.mapping-sheet {
  position: relative;
  display: grid;
  grid-template-columns: minmax(230px, 1fr) minmax(230px, 1fr);
  gap: 120px;
  min-width: 680px;
  padding: 16px;
  box-sizing: border-box;
}
.tree-column {
  min-width: 0;
  position: relative;
}
.tree-column h3 {
  margin: 4px 0 18px;
  font-size: 14px;
}
.tree-group {
  margin-bottom: 22px;
}
.graph-node {
  position: relative;
  z-index: 2;
  display: block;
  width: 100%;
  box-sizing: border-box;
  text-align: left;
  padding: 9px 12px;
  margin: 7px 0;
  border: 1px solid #c4d2e4;
  border-radius: 4px;
  color: var(--text);
  background: white;
  cursor: pointer;
  overflow-wrap: anywhere;
  font: inherit;
  font-size: 13px;
}
.graph-node[draggable='true'] {
  cursor: grab;
}
.graph-node small,
.field-group small {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  color: var(--muted);
  overflow-wrap: anywhere;
}
.graph-node:focus-visible,
.graph-node.selected {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
}
.resource-node,
.class-node {
  background: #eaf1fb;
  border-color: #a2bbdf;
  font-weight: 600;
}
.field-group {
  padding-left: 12px;
  border-left: 2px solid #dae3ef;
  margin: 10px 0 0 6px;
}
.field-group p {
  font-size: 11px;
  overflow-wrap: anywhere;
}
.property-node {
  width: calc(100% - 12px);
  margin-left: 12px;
}
.unmapped-field {
  color: var(--muted);
  padding: 7px;
  font-size: 12px;
}
.mapping-lines {
  position: absolute;
  z-index: 1;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
  overflow: visible;
  pointer-events: none;
}
.edge-hit {
  stroke: transparent;
  stroke-width: 14;
  fill: none;
  pointer-events: stroke;
  cursor: pointer;
}
.edge-line {
  stroke: #6588b9;
  stroke-width: 2;
  fill: none;
  pointer-events: none;
}
.edge-line.relation {
  stroke: #5a8c77;
}
.edge-line.chosen,
.edge-hit:focus + .edge-line {
  stroke: #c25d14;
  stroke-width: 4;
}
.draft-line {
  fill: none;
  stroke: #c25d14;
  stroke-width: 2;
  stroke-dasharray: 6 4;
}
.graph-node:disabled {
  cursor: default;
  opacity: 0.7;
}
select {
  padding: 5px;
  border: 1px solid var(--line);
  border-radius: 4px;
  background: white;
}
@media (max-width: 600px) {
  .mapping-viewport {
    max-height: 460px;
  }
}
</style>
