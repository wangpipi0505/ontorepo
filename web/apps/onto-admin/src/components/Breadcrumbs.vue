<script setup lang="ts">
/** 空间内面包屑；点击上级回到容器默认页，当前页只读。 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { breadcrumbs } from '../composables/breadcrumbs'
const route = useRoute()
const items = computed(() =>
  breadcrumbs(
    route.path,
    typeof route.query.tab === 'string' ? route.query.tab : undefined,
  ),
)
</script>
<template>
  <nav v-if="items.length" class="breadcrumbs" aria-label="面包屑">
    <template v-for="(item, index) in items" :key="`${item.label}-${index}`">
      <span v-if="index" class="separator" aria-hidden="true">/</span>
      <RouterLink v-if="item.to" :to="item.to">{{ item.label }}</RouterLink>
      <span v-else :class="{ current: index === items.length - 1 }">{{ item.label }}</span>
    </template>
  </nav>
</template>
<style scoped>
.breadcrumbs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  padding: 8px 14px 0;
  font-size: 12px;
  color: var(--muted);
}
.breadcrumbs a {
  color: var(--primary);
  text-decoration: none;
}
.breadcrumbs .current {
  color: var(--text);
}
.separator {
  color: var(--line);
}
@media (max-width: 780px) {
  .breadcrumbs {
    padding: 8px 16px 0;
  }
}
</style>
