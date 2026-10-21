<script setup lang="ts">
/** 操作容器：定义、助手声明、执行策略共用一个入口，旧路径 redirect 到对应页签。 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import OperationsView from './OperationsView.vue'
import AssistantView from '../features/p2/AssistantView.vue'
import StrategiesView from '../features/p1/StrategiesView.vue'
const route = useRoute(),
  router = useRouter()
const tab = computed<'definitions' | 'assistant' | 'strategies'>(() => {
  if (route.query.tab === 'assistant') return 'assistant'
  if (route.query.tab === 'strategies') return 'strategies'
  return 'definitions'
})
function select(name: string | number) {
  void router.replace({ query: { ...route.query, tab: String(name) } })
}
</script>
<template>
  <el-tabs :model-value="tab" class="operation-tabs" @tab-change="select">
    <el-tab-pane label="操作定义" name="definitions">
      <OperationsView v-if="tab === 'definitions'" />
    </el-tab-pane>
    <el-tab-pane label="助手声明" name="assistant">
      <AssistantView v-if="tab === 'assistant'" />
    </el-tab-pane>
    <el-tab-pane label="执行策略" name="strategies">
      <StrategiesView v-if="tab === 'strategies'" />
    </el-tab-pane>
  </el-tabs>
</template>
<style scoped>
.operation-tabs {
  padding: 0 4px;
}
</style>
