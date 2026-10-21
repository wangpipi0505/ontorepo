<script setup lang="ts">
import type { ReleaseDiff } from '../../../../../packages/api-client/src/release'
defineProps<{ diff: ReleaseDiff }>()
const sections = [
  { key: 'added', label: '新增' },
  { key: 'removed', label: '移除' },
  { key: 'changed', label: '变更' },
] as const
</script>
<template>
  <div class="stack">
    <section v-for="section in sections" :key="section.key">
      <h3>{{ section.label }} · {{ diff[section.key].length }}</h3>
      <p v-if="!diff[section.key].length" class="muted">无{{ section.label }}实体</p>
      <details v-for="item in diff[section.key]" :key="`${item.kind}:${item.key}`" class="record">
        <summary>{{ item.kind }} · {{ item.key }}</summary>
        <div class="form-columns">
          <div v-if="item.before !== undefined">
            <h4>原内容</h4>
            <pre>{{ JSON.stringify(item.before, null, 2) }}</pre>
          </div>
          <div v-if="item.after !== undefined">
            <h4>新内容</h4>
            <pre>{{ JSON.stringify(item.after, null, 2) }}</pre>
          </div>
        </div>
      </details>
    </section>
  </div>
</template>
