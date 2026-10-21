<script setup lang="ts">
/** 发布与服务容器：正式版本与事件收件箱共用一个入口，事件按版本上下文过滤。 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ReleasesView from '../features/p4/ReleasesView.vue'
import EventsView from '../features/p4/EventsView.vue'
import PublicationView from '../features/p4/PublicationView.vue'
const route = useRoute(),
  router = useRouter()
const tab = computed<'publication' | 'versions' | 'events'>(() => {
  if (route.query.tab === 'events') return 'events'
  if (route.query.tab === 'publication') return 'publication'
  return 'versions'
})
function select(name: string | number) {
  void router.replace({ query: { ...route.query, tab: String(name) } })
}
</script>
<template>
  <el-tabs :model-value="tab" class="release-tabs" @tab-change="select">
    <el-tab-pane label="发布向导" name="publication">
      <PublicationView v-if="tab === 'publication'" />
    </el-tab-pane>
    <el-tab-pane label="正式版本" name="versions">
      <ReleasesView v-if="tab === 'versions'" />
    </el-tab-pane>
    <el-tab-pane label="事件与收件箱" name="events">
      <EventsView v-if="tab === 'events'" />
    </el-tab-pane>
  </el-tabs>
</template>
<style scoped>
.release-tabs {
  padding: 0 4px;
}
</style>
