<script setup lang="ts">
/** 空间设置容器：成员授权与守卫声明共用一个入口，旧路径 redirect 到对应页签。 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import GuardsView from '../features/p2/GuardsView.vue'
import GrantsView from '../features/p2/GrantsView.vue'
const route = useRoute(),
  router = useRouter()
const tab = computed<'guards' | 'members'>(() =>
  route.query.tab === 'members' ? 'members' : 'guards',
)
function select(name: string | number) {
  void router.replace({ query: { ...route.query, tab: String(name) } })
}
</script>
<template>
  <el-tabs :model-value="tab" class="settings-tabs" @tab-change="select">
    <el-tab-pane label="守卫属性" name="guards">
      <GuardsView v-if="tab === 'guards'" />
    </el-tab-pane>
    <el-tab-pane label="成员与授权" name="members">
      <GrantsView v-if="tab === 'members'" />
    </el-tab-pane>
  </el-tabs>
</template>
<style scoped>
.settings-tabs {
  padding: 0 4px;
}
</style>
