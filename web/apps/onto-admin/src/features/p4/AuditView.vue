<script setup lang="ts">
/** 游标按服务端倒序连续读取；后页追加，不覆盖已经展示的审计事实。 */
import { ref, shallowRef, watch } from 'vue'
import type { AuditPage } from '../../../../../packages/api-client/src/release'
import { useWorkspace } from '../../stores/workspace'
import { useSession } from '../../stores/session'
import { useResource } from '../../composables/useResource'
import './p4.css'
const workspace = useWorkspace(),
  api = useSession().api,
  request = useResource<AuditPage | null>(null)
const items = shallowRef<AuditPage['items']>([]),
  cursor = ref<number | null>(null),
  initialized = ref(false),
  lastAppend = ref(false)
let generation = 0
async function load(append = false) {
  const id = workspace.space?.id,
    ticket = generation
  if (!id || request.loading.value || (append && cursor.value === null)) return
  lastAppend.value = append
  const result = await request.load(() =>
    api.get<AuditPage>(`/audit?spaceId=${id}&limit=50${append ? `&beforeId=${cursor.value}` : ''}`),
  )
  if (!result || ticket !== generation) return
  const known = new Set(append ? items.value.map((item) => item.id) : [])
  items.value = [
    ...(append ? items.value : []),
    ...result.items.filter((item) => !known.has(item.id)),
  ]
  cursor.value = result.nextBeforeId
  initialized.value = true
}
watch(
  () => workspace.space?.id,
  () => {
    generation++
    request.reset()
    items.value = []
    cursor.value = null
    initialized.value = false
    void load()
  },
  { immediate: true },
)
</script>
<template>
  <div class="p4-page stack">
    <div class="page-heading">
      <div>
        <h1>审计记录</h1>
        <p class="muted">按时间查看模型、发布、授权与人工重放等处理活动。</p>
      </div>
      <el-button :disabled="request.loading.value" @click="load()">刷新审计</el-button>
    </div>
    <section class="panel stack">
      <p v-if="request.error.value" class="error-text" role="alert">
        {{ request.error.value }}
        <el-button :disabled="request.loading.value" @click="load(lastAppend)">重试读取</el-button>
      </p>
      <p v-if="request.loading.value" role="status" class="muted">正在读取审计记录…</p>
      <p v-if="initialized && !items.length" class="muted">当前空间暂无审计活动。</p>
      <article v-for="item in items" :key="item.id" class="record">
        <div class="row spread">
          <b>#{{ item.id }} · {{ item.action }}</b>
          <time>{{ item.createdAt }}</time>
        </div>
        <p class="muted">操作人 {{ item.actorId }} · 对象 {{ item.subjectId ?? '未记录' }}</p>
        <details>
          <summary>安全审计详情</summary>
          <pre>{{ JSON.stringify(item.detail, null, 2) }}</pre>
        </details>
      </article>
      <div v-if="cursor !== null">
        <el-button :disabled="request.loading.value" @click="load(true)">加载更早记录</el-button>
      </div>
    </section>
  </div>
</template>
