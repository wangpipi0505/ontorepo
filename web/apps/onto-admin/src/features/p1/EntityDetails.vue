<script setup lang="ts">
/** 结构化定义按文本转义展示，查看者也能检查评分、断言与读取范围的完整内容。 */
defineProps<{ item: unknown }>()
</script>
<template>
  <pre class="entity-details">{{ JSON.stringify(item, null, 2) }}</pre>
</template>
<style scoped>
.entity-details {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--canvas);
  padding: 16px;
  font-size: 12px;
  margin: 0;
  line-height: 1.7;
}
</style>
