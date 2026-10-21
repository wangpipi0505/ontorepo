<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { IamAuditPage } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useSession } from '../../stores/session'
import { useResource } from '../../composables/useResource'
const api = useSession().api,
  page = ref(1),
  pageSize = 20,
  state = useResource<IamAuditPage | null>(null)
function load() {
  return state.load(() =>
    api.get<IamAuditPage>(`/system/audit?page=${page.value}&pageSize=${pageSize}`),
  )
}
async function move(delta: number) {
  page.value += delta
  await load()
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>系统管理审计</h1>
      <p class="muted">查看用户与角色变更的操作者、修订及安全快照。</p>
    </div>
    <el-button @click="load">刷新</el-button>
  </div>
  <AsyncState :loading="state.loading.value" :error="state.error.value" @retry="load">
    <section v-if="state.data.value" class="panel stack">
      <p class="muted">第 {{ state.data.value.page }} 页，共 {{ state.data.value.total }} 条</p>
      <article v-for="item in state.data.value.items" :key="item.id" class="audit-record">
        <div class="row spread">
          <b>{{ item.action }}</b>
          <span class="muted">{{ new Date(item.createdAt).toLocaleString('zh-CN') }}</span>
        </div>
        <p>
          {{ item.actorName }} ·
          <span class="mono">{{ item.targetId }}</span>
        </p>
        <details>
          <summary>变更前后</summary>
          <div class="audit-snapshots">
            <pre>{{ JSON.stringify(item.beforeState, null, 2) }}</pre>
            <pre>{{ JSON.stringify(item.afterState, null, 2) }}</pre>
          </div>
        </details>
      </article>
      <p v-if="!state.data.value.items.length" class="muted">暂无系统管理记录。</p>
      <div class="row">
        <el-button :disabled="page <= 1 || state.loading.value" @click="move(-1)">上一页</el-button>
        <el-button
          :disabled="page * pageSize >= state.data.value.total || state.loading.value"
          @click="move(1)"
        >
          下一页
        </el-button>
      </div>
    </section>
  </AsyncState>
</template>
<style scoped>
.audit-record {
  border: 1px solid var(--line);
  padding: 16px;
  border-radius: 6px;
  overflow-wrap: anywhere;
}
.audit-snapshots {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.audit-snapshots pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  min-width: 0;
}
@media (max-width: 700px) {
  .audit-snapshots {
    grid-template-columns: 1fr;
  }
}
</style>
