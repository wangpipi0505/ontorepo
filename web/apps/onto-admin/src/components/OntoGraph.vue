<script setup lang="ts">
/** 本体图谱：类为节点、关系为有向边；支持拖拽/缩放/拉线建关系与冲突高亮。 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Graph } from '@antv/g6'

export interface GraphNode {
  id: string
  label: string
  conflict?: boolean
}
export interface GraphEdge {
  id: string
  source: string
  target: string
  label: string
}
const props = withDefaults(
  defineProps<{
    nodes: GraphNode[]
    edges: GraphEdge[]
    selectedId?: string
    editable?: boolean
    linkMode?: boolean
    height?: number
  }>(),
  { selectedId: '', editable: false, linkMode: false, height: 520 },
)
const emit = defineEmits<{
  select: [id: string]
  edit: [id: string]
  'create-edge': [payload: { source: string; target: string }]
  'create-node': []
}>()
const container = ref<HTMLDivElement>()
let graph: Graph | null = null
let observer: ResizeObserver | null = null

function data() {
  return {
    nodes: props.nodes.map((node) => ({
      id: node.id,
      type: 'rect',
      data: { label: node.label, conflict: node.conflict === true },
      style: { fill: node.id === props.selectedId ? '#dbeafe' : '#ffffff' },
    })),
    edges: props.edges.map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      data: { label: edge.label },
    })),
  }
}

async function render() {
  if (!container.value) return
  if (!graph) {
    graph = new Graph({
      container: container.value,
      autoFit: 'center',
      data: data(),
      node: {
        style: {
          size: [140, 44],
          radius: 8,
          fill: '#ffffff',
          stroke: (datum: { data?: { conflict?: boolean } }) =>
            datum.data?.conflict ? '#dc2626' : '#2563eb',
          lineWidth: (datum: { data?: { conflict?: boolean } }) =>
            datum.data?.conflict ? 3 : 1.5,
          labelText: (datum: { data?: { label?: string } }) => datum.data?.label ?? '',
          labelPlacement: 'center',
          labelFill: '#1f2937',
          labelFontSize: 13,
        },
      },
      edge: {
        style: {
          stroke: '#94a3b8',
          endArrow: true,
          labelText: (datum: { data?: { label?: string } }) => datum.data?.label ?? '',
          labelFontSize: 11,
          labelFill: '#475569',
        },
      },
      layout: { type: 'antv-dagre', rankdir: 'LR', nodesep: 30, ranksep: 70 },
      behaviors: [
        'drag-canvas',
        'zoom-canvas',
        ...(props.linkMode ? [] : ['drag-element']),
        ...(props.editable
          ? [
              {
                type: 'create-edge',
                trigger: props.linkMode ? 'drag' : 'click',
                onFinish: (edge: { source: string; target: string }) => {
                  if (edge.source && edge.target && edge.source !== edge.target)
                    emit('create-edge', { source: edge.source, target: edge.target })
                },
              },
            ]
          : []),
      ],
    })
    graph.on('node:click', (event) => {
      const id = (event as unknown as { target?: { id?: string } }).target?.id
      if (id) emit('select', id)
    })
    graph.on('node:dblclick', (event) => {
      const id = (event as unknown as { target?: { id?: string } }).target?.id
      if (id && props.editable) emit('edit', id)
    })
    graph.on('canvas:dblclick', () => {
      if (props.editable) emit('create-node')
    })
    await graph.render()
  } else {
    graph.setData(data())
    await graph.render()
  }
}

function updateSelection() {
  if (!graph) return
  for (const node of props.nodes)
    graph.updateNodeData([
      {
        id: node.id,
        style: { fill: node.id === props.selectedId ? '#dbeafe' : '#ffffff' },
      },
    ])
  graph.draw()
}

onMounted(async () => {
  await render()
  if (container.value)
    observer = new ResizeObserver(() => graph?.resize())
  if (container.value && observer) observer.observe(container.value)
})
watch(() => [props.nodes, props.edges], render, { deep: true })
watch(() => props.selectedId, updateSelection)
watch(
  () => props.linkMode,
  async () => {
    graph?.destroy()
    graph = null
    await render()
  },
)
onBeforeUnmount(() => {
  observer?.disconnect()
  graph?.destroy()
  graph = null
})
</script>
<template>
  <div class="onto-graph" :style="{ height: `${height}px` }">
    <div ref="container" class="graph-canvas" />
    <p v-if="editable" class="graph-hint">
      {{
        linkMode
          ? '连线模式：从节点拖到另一节点创建关系；关闭后可拖动布局。'
          : '拖拽节点调整布局；依次点击两个对象可新建关系；双击节点编辑，双击空白新建对象。'
      }}
    </p>
  </div>
</template>
<style scoped>
.onto-graph {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 100%;
  background: #f8fafc;
  border: 1px solid var(--line);
  border-radius: 8px;
  overflow: hidden;
}
.graph-canvas {
  flex: 1;
  width: 100%;
  min-height: 0;
}
.graph-hint {
  margin: 0;
  padding: 6px 12px;
  border-top: 1px solid var(--line);
  background: #ffffff;
  color: var(--muted, #64748b);
  font-size: 12px;
}
</style>
