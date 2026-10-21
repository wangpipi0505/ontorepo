<script setup lang="ts">
/** 建模工作台容器：对象、码表、关系、约束、规则、冲突共用一个入口。 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ModelView from './ModelView.vue'
import CodebooksView from '../features/p1/CodebooksView.vue'
import RelationsView from '../features/p1/RelationsView.vue'
import ConstraintsView from '../features/p1/ConstraintsView.vue'
import RulesView from '../features/p1/RulesView.vue'
import ConflictsView from '../features/p1/ConflictsView.vue'
const route = useRoute(),
  router = useRouter()
type ModelTab = 'classes' | 'codebooks' | 'relations' | 'constraints' | 'rules' | 'conflicts'
const tabs: { name: ModelTab; label: string }[] = [
  { name: 'classes', label: '对象与属性' },
  { name: 'codebooks', label: '码表' },
  { name: 'relations', label: '关系' },
  { name: 'constraints', label: '约束' },
  { name: 'rules', label: '规则' },
  { name: 'conflicts', label: '冲突检查' },
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
        <CodebooksView v-else-if="tab === 'codebooks'" />
        <RelationsView v-else-if="tab === 'relations'" />
        <ConstraintsView v-else-if="tab === 'constraints'" />
        <RulesView v-else-if="tab === 'rules'" />
        <ConflictsView v-else />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
