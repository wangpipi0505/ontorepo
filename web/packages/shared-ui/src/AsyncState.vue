<script setup lang="ts">
/** 统一页面状态：失败显式给重试入口，空态不伪造业务数据。 */
defineProps<{ loading?: boolean; error?: string; empty?: boolean; emptyText?: string }>()
defineEmits<{ retry: [] }>()
</script>
<template>
  <div v-if="loading" class="state-block" role="status">正在读取…</div>
  <div v-else-if="error" class="state-block state-error" role="alert">
    <p>{{ error }}</p>
    <button class="plain-button" @click="$emit('retry')">重新加载</button>
  </div>
  <div v-else-if="empty" class="state-block">{{ emptyText || '暂无记录' }}</div>
  <slot v-else />
</template>
