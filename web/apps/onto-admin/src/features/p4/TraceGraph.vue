<script setup lang="ts">
/** 图与可读列表共享服务端事实；节点点击查看版本/引用，缺失边端点不补造节点。 */
import { computed, ref } from 'vue'
import type { TraceGraphData } from '../../../../../packages/api-client/src/release'
import OntoGraph, { type GraphEdge, type GraphNode } from '../../components/OntoGraph.vue'
const props = defineProps<{ graph: TraceGraphData }>()
const emit = defineEmits<{ 'select-node': [id: string] }>()
const selectedId = ref('')
const nodes = computed<GraphNode[]>(() =>
  props.graph.nodes.map((node) => ({
    id: node.id,
    label: `${node.label} · ${node.kind}`,
  })),
)
const edges = computed<GraphEdge[]>(() =>
  props.graph.edges.map((edge) => ({
    id: edge.id,
    source: edge.from,
    target: edge.to,
    label: edge.label,
  })),
)
const selected = computed(() => props.graph.nodes.find((node) => node.id === selectedId.value))
function selectNode(id: string) {
  selectedId.value = id
  emit('select-node', id)
}
</script>
<template>
  <section class="stack">
    <div class="row spread">
      <h3>执行关系图</h3>
      <span class="muted">{{ graph.nodes.length }} 节点 · {{ graph.edges.length }} 关系</span>
    </div>
    <p v-if="!graph.nodes.length" class="muted">此记录未保存图节点。</p>
    <template v-else>
      <OntoGraph
        :nodes="nodes"
        :edges="edges"
        :selected-id="selectedId"
        :height="480"
        @select="selectNode"
      />
      <div v-if="selected" class="record">
        <b>{{ selected.label }} · {{ selected.kind }}</b>
        <p class="identity">引用：{{ selected.ref ?? '未记录' }}</p>
        <p class="muted">节点标识：{{ selected.id }}</p>
      </div>
      <p v-else class="muted">点击画布节点查看引用与证据标识。</p>
    </template>
    <details>
      <summary>可读节点与关系列表</summary>
      <ul>
        <li v-for="node in graph.nodes" :key="node.id">
          {{ node.label }} · {{ node.kind }} · {{ node.ref ?? node.id }}
        </li>
      </ul>
      <ul>
        <li v-for="edge in graph.edges" :key="edge.id">
          {{ edge.from }} → {{ edge.to }} · {{ edge.label }}
        </li>
      </ul>
    </details>
  </section>
</template>
<style scoped>
li {
  overflow-wrap: anywhere;
  margin: 8px 0;
  font-size: 13px;
}
</style>
