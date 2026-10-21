<script setup lang="ts">
/** 测试与服务封装：用例/候选验证与发布、消费在同一模块，避免跨模块跳转。 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TestingView from './TestingView.vue'
import ReleaseHubView from './ReleaseHubView.vue'
import GlobalDeliveryView from './GlobalDeliveryView.vue'
const route = useRoute(),
  router = useRouter()
const spaceId = computed(() =>
  typeof route.query.spaceId === 'string' ? route.query.spaceId : '',
)
type Tab = 'testing' | 'delivery'
const tab = computed<Tab>(() => (route.query.tab === 'delivery' ? 'delivery' : 'testing'))
function select(name: string | number) {
  void router.replace({ query: { ...route.query, tab: String(name) } })
}
</script>
<template>
  <GlobalDeliveryView v-if="!spaceId" />
  <section v-else class="module-page">
    <header class="module-heading">
      <div>
        <h1>测试与服务封装</h1>
        <p class="muted">冻结候选验证、发布门禁、正式版本与服务消费；测试结果直接进入发布链路。</p>
      </div>
    </header>
    <el-tabs :model-value="tab" class="module-tabs" @tab-change="select">
      <el-tab-pane label="验证与候选" name="testing">
        <TestingView v-if="tab === 'testing'" />
      </el-tab-pane>
      <el-tab-pane label="发布与服务" name="delivery">
        <ReleaseHubView v-if="tab === 'delivery'" />
      </el-tab-pane>
    </el-tabs>
  </section>
</template>
