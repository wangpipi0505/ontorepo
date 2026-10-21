<script setup lang="ts">
/** 验证与候选容器：用例与候选共用一个空间入口，旧路径 redirect 到对应页签。 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TestCasesView from '../features/p1/TestCasesView.vue'
import CandidatesView from './CandidatesView.vue'
import SupplyView from '../features/p2/SupplyView.vue'
const route = useRoute(),
  router = useRouter()
const tab = computed<'cases' | 'candidates' | 'supply'>(() => {
  if (route.query.tab === 'candidates') return 'candidates'
  if (route.query.tab === 'supply') return 'supply'
  return 'cases'
})
function select(name: string | number) {
  void router.replace({ query: { ...route.query, tab: String(name) } })
}
</script>
<template>
  <el-tabs :model-value="tab" class="testing-tabs" @tab-change="select">
    <el-tab-pane label="用例与试执行" name="cases">
      <TestCasesView v-if="tab === 'cases'" />
    </el-tab-pane>
    <el-tab-pane label="候选与试执行" name="candidates">
      <CandidatesView v-if="tab === 'candidates'" />
    </el-tab-pane>
    <el-tab-pane label="候选供给" name="supply">
      <SupplyView v-if="tab === 'supply'" />
    </el-tab-pane>
  </el-tabs>
</template>
<style scoped>
.testing-tabs {
  padding: 0 4px;
}
</style>
