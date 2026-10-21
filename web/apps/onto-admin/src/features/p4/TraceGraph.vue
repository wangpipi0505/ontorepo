<script setup lang="ts">
/** 图与可读列表共享服务端事实；缺失边端点不补造节点，仍在关系列表展示。 */
import { computed, useId } from 'vue'
import type { TraceGraphData } from '../../../../../packages/api-client/src/release'
const props = defineProps<{ graph: TraceGraphData }>()
const marker = `arrow-${useId().replaceAll(':', '')}`
const nodes = computed(() =>
  props.graph.nodes.map((node, index) => ({
    ...node,
    x: index % 2 === 0 ? 25 : 345,
    y: 25 + Math.floor(index / 2) * 115,
  })),
)
const edges = computed(() =>
  props.graph.edges.flatMap((edge) => {
    const from = nodes.value.find((node) => node.id === edge.from),
      to = nodes.value.find((node) => node.id === edge.to)
    return from && to
      ? [{ ...edge, x1: from.x + 115, y1: from.y + 58, x2: to.x + 115, y2: to.y }]
      : []
  }),
)
const height = computed(() => Math.max(130, Math.ceil(nodes.value.length / 2) * 115 + 25))
</script>
<template>
  <section class="stack">
    <h3>执行关系图</h3>
    <p v-if="!nodes.length" class="muted">此记录未保存图节点。</p>
    <div v-else class="graph-frame">
      <svg :viewBox="`0 0 600 ${height}`" role="img" aria-label="由实际执行节点和关联生成的追溯图">
        <defs>
          <marker :id="marker" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto">
            <path d="M0,0 L8,3 L0,6" fill="#64748b" />
          </marker>
        </defs>
        <g v-for="edge in edges" :key="edge.id" :data-edge-id="edge.id">
          <path
            :d="`M ${edge.x1} ${edge.y1} L ${edge.x2} ${edge.y2}`"
            fill="none"
            stroke="#64748b"
            :marker-end="`url(#${marker})`"
          />
          <title>{{ edge.label }}</title>
        </g>
        <g
          v-for="node in nodes"
          :key="node.id"
          :data-node-id="node.id"
          :transform="`translate(${node.x},${node.y})`"
        >
          <rect width="230" height="58" rx="6" fill="#eef4ff" stroke="#93b4fc" />
          <text x="12" y="23" fill="#172b46">
            {{ node.label.length > 19 ? node.label.slice(0, 19) + '…' : node.label }}
          </text>
          <text x="12" y="44" fill="#64748b" font-size="10">{{ node.kind }}</text>
          <title>{{ node.label }} · {{ node.ref ?? node.id }}</title>
        </g>
      </svg>
    </div>
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
.graph-frame {
  border: 1px solid var(--line);
  border-radius: 6px;
  overflow: auto;
  max-height: 650px;
}
svg {
  display: block;
  width: 100%;
  min-width: 350px;
  font:
    13px 'Microsoft YaHei',
    sans-serif;
}
li {
  overflow-wrap: anywhere;
  margin: 8px 0;
  font-size: 13px;
}
</style>
