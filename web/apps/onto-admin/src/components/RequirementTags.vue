<script setup lang="ts">
/** 页头技术要求角标；页面映射集中维护，便于验收逐条定位。 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { requirementTags } from '../composables/requirementTags'
const route = useRoute()
const tags = computed(() => requirementTags(route.path))
</script>
<template>
  <span v-if="tags.length" class="requirement-tags" aria-label="技术要求角标">
    <span v-for="tag in tags" :key="tag">{{ tag }}</span>
  </span>
</template>
<style scoped>
.requirement-tags {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-left: 8px;
}
.requirement-tags span {
  padding: 1px 6px;
  border-radius: 10px;
  background: #eef4ff;
  color: #1d4ed8;
  font-size: 11px;
  line-height: 18px;
}
</style>
