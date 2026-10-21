<script setup lang="ts">
import { useRoute } from 'vue-router'
import ExecutionTracePanel from './ExecutionTracePanel.vue'
import './p4.css'
const route = useRoute()
</script>
<template>
  <div class="p4-page stack">
    <div class="page-heading">
      <div>
        <h1>执行追溯</h1>
        <p class="muted">从实际执行记录查看请求、规则、查询、授权与证据关系。</p>
      </div>
      <RouterLink :to="`/spaces/${route.params.spaceId}/executions`">返回执行记录</RouterLink>
    </div>
    <ExecutionTracePanel :execution-id="String(route.params.executionId ?? '')" />
  </div>
</template>
