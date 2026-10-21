<script setup lang="ts">
/** 建模工作台容器：画布为主线，规则/冲突/策略/操作为表格视图。 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ModelView from './ModelView.vue'
import CodebooksView from '../features/p1/CodebooksView.vue'
import RelationsView from '../features/p1/RelationsView.vue'
import ConstraintsView from '../features/p1/ConstraintsView.vue'
import RulesView from '../features/p1/RulesView.vue'
import ConflictsView from '../features/p1/ConflictsView.vue'
import StrategiesView from '../features/p1/StrategiesView.vue'
import OperationsView from './OperationsView.vue'
const route = useRoute(),
  router = useRouter()
type ModelTab =
  | 'classes'
  | 'codebooks'
  | 'relations'
  | 'constraints'
  | 'rules'
  | 'conflicts'
  | 'strategies'
  | 'operations'
const tabs: { name: ModelTab; label: string }[] = [
  { name: 'classes', label: '建模画布' },
  { name: 'rules', label: '规则' },
  { name: 'conflicts', label: '冲突检测' },
  { name: 'strategies', label: '策略' },
  { name: 'operations', label: '操作' },
  { name: 'relations', label: '关系表' },
  { name: 'constraints', label: '约束' },
  { name: 'codebooks', label: '码表' },
]
const tab = computed<ModelTab>(() => {
  const value = String(route.query.tab || '')
  return tabs.some((item) => item.name === value) ? (value as ModelTab) : 'classes'
})
function select(name: string | number) {
  void router.replace({ query: { ...route.query, tab: String(name) } })
}
</script>
<template>
  <div class="model-hub">
    <el-tabs :model-value="tab" @tab-change="select">
      <el-tab-pane v-for="item in tabs" :key="item.name" :label="item.label" :name="item.name">
        <ModelView v-if="tab === 'classes'" />
        <RulesView v-else-if="tab === 'rules'" />
        <ConflictsView v-else-if="tab === 'conflicts'" />
        <StrategiesView v-else-if="tab === 'strategies'" />
        <OperationsView v-else-if="tab === 'operations'" />
        <RelationsView v-else-if="tab === 'relations'" />
        <ConstraintsView v-else-if="tab === 'constraints'" />
        <CodebooksView v-else />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
