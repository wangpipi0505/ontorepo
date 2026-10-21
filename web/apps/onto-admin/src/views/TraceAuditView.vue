<script setup lang="ts">
/** 数据溯源审计：执行列表/逐执行追溯与审计记录在同一模块。 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ExecutionsView from './ExecutionsView.vue'
import AuditView from '../features/p4/AuditView.vue'
const route = useRoute(),
  router = useRouter()
type Tab = 'executions' | 'audit'
const tab = computed<Tab>(() => (route.query.tab === 'audit' ? 'audit' : 'executions'))
function select(name: string | number) {
  void router.replace({ query: { ...route.query, tab: String(name) } })
}
</script>
<template>
  <section class="module-page">
    <header class="module-heading">
      <div>
        <h1>数据溯源审计</h1>
        <p class="muted">按执行查看数据来源、语义映射、规则/操作与输出证据；审计记录支持对象/动作/时间检索。</p>
      </div>
    </header>
    <el-tabs :model-value="tab" class="module-tabs" @tab-change="select">
      <el-tab-pane label="执行与追溯" name="executions">
        <ExecutionsView v-if="tab === 'executions'" />
      </el-tab-pane>
      <el-tab-pane label="审计记录" name="audit">
        <AuditView v-if="tab === 'audit'" />
      </el-tab-pane>
    </el-tabs>
  </section>
</template>
