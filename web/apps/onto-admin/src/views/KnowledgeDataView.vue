<script setup lang="ts">
/** 知识与数据模块：知识物料、数据源、字段映射、目录与导入统一在同一空间上下文。 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import KnowledgeView from '../features/p2/KnowledgeView.vue'
import SourcesView from '../features/p3/SourcesView.vue'
import MappingsView from '../features/p3/MappingsView.vue'
import DataCatalogView from '../features/p3/DataCatalogView.vue'
import SuggestionsView from '../features/p3/SuggestionsView.vue'
import ImportsView from '../features/p1/ImportsView.vue'
const route = useRoute(),
  router = useRouter()
type Tab = 'knowledge' | 'datasources' | 'mappings' | 'catalog' | 'suggestions' | 'imports'
const tabs: { name: Tab; label: string }[] = [
  { name: 'knowledge', label: '知识物料' },
  { name: 'datasources', label: '数据源' },
  { name: 'mappings', label: '字段映射' },
  { name: 'catalog', label: '数据目录' },
  { name: 'suggestions', label: '从数据抽取' },
  { name: 'imports', label: 'TTL / OWL 导入' },
]
const tab = computed<Tab>(() => {
  const value = String(route.query.tab || '')
  return tabs.some((item) => item.name === value) ? (value as Tab) : 'knowledge'
})
function select(name: string | number) {
  void router.replace({ query: { ...route.query, tab: String(name) } })
}
</script>
<template>
  <section class="module-page">
    <header class="module-heading">
      <div>
        <h1>知识与数据</h1>
        <p class="muted">
          统一管理领域知识物料、数据源连接、字段映射与元数据快照；供当前本体空间建模时引用和绑定。
        </p>
      </div>
    </header>
    <el-tabs :model-value="tab" class="module-tabs" @tab-change="select">
      <el-tab-pane label="知识物料" name="knowledge">
        <KnowledgeView v-if="tab === 'knowledge'" />
      </el-tab-pane>
      <el-tab-pane label="数据源" name="datasources">
        <SourcesView v-if="tab === 'datasources'" />
      </el-tab-pane>
      <el-tab-pane label="字段映射" name="mappings">
        <MappingsView v-if="tab === 'mappings'" />
      </el-tab-pane>
      <el-tab-pane label="数据目录" name="catalog">
        <DataCatalogView v-if="tab === 'catalog'" />
      </el-tab-pane>
      <el-tab-pane label="从数据抽取" name="suggestions">
        <SuggestionsView v-if="tab === 'suggestions'" />
      </el-tab-pane>
      <el-tab-pane label="TTL / OWL 导入" name="imports">
        <ImportsView v-if="tab === 'imports'" />
      </el-tab-pane>
    </el-tabs>
  </section>
</template>
