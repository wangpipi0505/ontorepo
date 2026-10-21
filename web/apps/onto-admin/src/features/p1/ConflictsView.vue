<script setup lang="ts">
import DraftConflictsPanel from './DraftConflictsPanel.vue'
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>冲突检查</h1>
      <p class="muted">按引用、类型、约束和逻辑检查当前草稿，点击关联条目定位到对应模型页面。</p>
    </div>
  </div>
  <DraftConflictsPanel />
</template>
