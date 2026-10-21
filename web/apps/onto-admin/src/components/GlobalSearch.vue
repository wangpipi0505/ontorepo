<script setup lang="ts">
/** 空间内全局搜索：按需读取对象/操作/规则/数据源目录，仅做本地子串过滤，不新增后端接口。 */
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { Datasource, OntClass, Operation, Rule } from '@onto/api-client'
import { useSession } from '../stores/session'
import { useWorkspace } from '../stores/workspace'
type HitKind = '对象' | '操作' | '规则' | '数据源'
interface Hit {
  id: string
  label: string
  note: string
  kind: HitKind
  to: string
}
const router = useRouter(),
  api = useSession().api,
  workspace = useWorkspace()
const query = ref(''),
  open = ref(false),
  loading = ref(false),
  hits = ref<Hit[]>([])
let loadedFor = ''
const results = computed(() => {
  const text = query.value.trim().toLowerCase()
  if (!text) return []
  return hits.value
    .filter((item) => `${item.label} ${item.note}`.toLowerCase().includes(text))
    .slice(0, 8)
})
async function load() {
  const id = workspace.space?.id
  if (!id || loadedFor === id) return
  loading.value = true
  const [classes, operations, rules, datasources] = await Promise.allSettled([
    api.get<OntClass[]>(`/classes?spaceId=${id}`),
    api.get<Operation[]>(`/operations?spaceId=${id}`),
    api.get<Rule[]>(`/rules?spaceId=${id}`),
    api.get<Datasource[]>(`/datasources?spaceId=${id}`),
  ])
  const next: Hit[] = []
  if (classes.status === 'fulfilled')
    next.push(
      ...classes.value.map((item) => ({
        id: item.id,
        label: item.name,
        note: item.id,
        kind: '对象' as const,
        to: `/spaces/${id}/model?classId=${item.id}`,
      })),
    )
  if (operations.status === 'fulfilled')
    next.push(
      ...operations.value.map((item) => ({
        id: item.id,
        label: item.name,
        note: item.code,
        kind: '操作' as const,
        to: `/spaces/${id}/operations?entityId=${item.id}`,
      })),
    )
  if (rules.status === 'fulfilled')
    next.push(
      ...rules.value.map((item) => ({
        id: item.id,
        label: item.name,
        note: item.kind,
        kind: '规则' as const,
        to: `/spaces/${id}/model?tab=rules`,
      })),
    )
  if (datasources.status === 'fulfilled')
    next.push(
      ...datasources.value.map((item) => ({
        id: item.id,
        label: item.name,
        note: item.code,
        kind: '数据源' as const,
        to: `/spaces/${id}/datasources`,
      })),
    )
  hits.value = next
  loadedFor = id
  loading.value = false
}
function go(item: Hit) {
  query.value = ''
  open.value = false
  void router.push(item.to)
}
watch(
  () => workspace.space?.id,
  () => {
    loadedFor = ''
    hits.value = []
    query.value = ''
  },
)
</script>
<template>
  <div class="global-search" role="search" @focusin="open = true">
    <el-input
      v-model="query"
      size="small"
      clearable
      placeholder="搜索对象 / 操作 / 规则 / 数据源"
      aria-label="空间内搜索"
      @focus="load"
      @keydown.esc="open = false"
      @keydown.enter="results.length && go(results[0]!)"
    />
    <div v-if="open && query.trim()" class="search-panel">
      <p v-if="loading" class="muted">正在读取空间目录…</p>
      <p v-else-if="!results.length" class="muted">没有匹配的条目。</p>
      <button v-for="item in results" :key="`${item.kind}-${item.id}`" type="button" @click="go(item)">
        <span class="kind">{{ item.kind }}</span>
        <b>{{ item.label }}</b>
        <span class="note">{{ item.note }}</span>
      </button>
    </div>
  </div>
</template>
<style scoped>
.global-search {
  position: relative;
  width: min(360px, 40vw);
}
.search-panel {
  position: absolute;
  top: 36px;
  left: 0;
  right: 0;
  z-index: 20;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 8px;
  box-shadow: 0 8px 24px rgb(15 23 42 / 12%);
  padding: 6px;
}
.search-panel button {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  width: 100%;
  border: 0;
  background: transparent;
  padding: 8px;
  text-align: left;
  cursor: pointer;
  border-radius: 6px;
}
.search-panel button:hover {
  background: #f1f5ff;
}
.kind {
  font-size: 11px;
  color: var(--primary);
}
.note {
  color: var(--muted);
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
@media (max-width: 780px) {
  .global-search {
    width: 100%;
  }
}
</style>
