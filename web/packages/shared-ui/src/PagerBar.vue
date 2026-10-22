<script setup lang="ts">
/** 全局列表的轻量分页条：只负责页码与总数展示，不持有业务筛选状态。 */
const props = defineProps<{ page: number; total: number; size?: number }>()
const emit = defineEmits<{ 'update:page': [page: number] }>()
const pageCount = () => Math.max(1, Math.ceil(props.total / (props.size ?? 30)))
function move(delta: number) {
  const next = Math.min(pageCount(), Math.max(1, props.page + delta))
  if (next !== props.page) emit('update:page', next)
}
</script>
<template>
  <div class="pager-bar">
    <span>共 {{ total }} 条 · 第 {{ page }} / {{ pageCount() }} 页</span>
    <div class="row">
      <button type="button" :disabled="page <= 1" @click="move(-1)">上一页</button>
      <button type="button" :disabled="page >= pageCount()" @click="move(1)">下一页</button>
    </div>
  </div>
</template>
<style scoped>
.pager-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 2px 2px;
  color: var(--muted);
  font-size: 12px;
}
.pager-bar button {
  border: 1px solid var(--line);
  background: #fff;
  color: var(--text);
  border-radius: 4px;
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;
}
.pager-bar button:disabled {
  color: #9aa8ba;
  cursor: not-allowed;
}
</style>
