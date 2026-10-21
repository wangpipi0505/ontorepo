<script setup lang="ts">
/** 数据溯源审计全局视图：跨可见本体汇总执行与审计，支持按本体检索。 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { Execution, Space } from '@onto/api-client'
import type { AuditPage } from '../../../../packages/api-client/src/release'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useSession } from '../stores/session'
import { useResource } from '../composables/useResource'
type ExecutionRow = Execution & { spaceName?: string }
type AuditRow = AuditPage['items'][number] & { spaceName?: string }
interface GlobalTrace {
  executions: ExecutionRow[]
  audit: AuditRow[]
}
const router = useRouter(),
  api = useSession().api,
  data = useResource<GlobalTrace | null>(null),
  spaces = useResource<Space[]>([])
const spaceFilter = ref(''),
  keyword = ref(''),
  tab = ref<'executions' | 'audit'>('executions')
const spaceNames = computed(() => new Map(spaces.data.value.map((item) => [item.id, item.name])))
function filterRows<T extends { spaceId?: string; spaceName?: string }>(rows: T[]) {
  const text = keyword.value.trim().toLocaleLowerCase()
  return rows
    .filter((row) => !spaceFilter.value || row.spaceId === spaceFilter.value)
    .filter((row) =>
      `${spaceNames.value.get(row.spaceId ?? '') ?? row.spaceName ?? ''}`
        .toLocaleLowerCase()
        .includes(text),
    )
}
const executions = computed(() => filterRows(data.data.value?.executions ?? []))
const audit = computed(() => filterRows(data.data.value?.audit ?? []))
async function load() {
  await Promise.all([
    data.load(() => api.get<GlobalTrace>('/global/trace')),
    spaces.load(() => api.get<Space[]>('/spaces')),
  ])
}
onMounted(load)
</script>
<template>
  <section class="module-page">
    <header class="module-heading">
      <div>
        <h1>数据溯源审计</h1>
        <p class="muted">跨本体按名称检索执行与审计；进入所属本体查看逐执行关系图与完整链路。</p>
      </div>
      <el-button @click="load">刷新全局视图</el-button>
    </header>
    <section class="panel">
      <div class="global-filter">
        <el-select v-model="spaceFilter" placeholder="全部本体" clearable>
          <el-option label="全部本体" value="" />
          <el-option
            v-for="space in spaces.data.value"
            :key="space.id"
            :label="space.name"
            :value="space.id"
          />
        </el-select>
        <el-input v-model="keyword" placeholder="按本体名称 / 编码筛选" clearable />
        <el-button @click="load">刷新</el-button>
      </div>
      <AsyncState :loading="data.loading.value" :error="data.error.value" @retry="load">
        <el-tabs v-model="tab">
          <el-tab-pane :label="`执行 ${executions.length}`" name="executions">
            <el-table :data="executions" row-key="id">
              <el-table-column label="执行" width="170">
                <template #default="{ row }">
                  <span class="mono">{{ String(row.id).slice(0, 8) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="所属本体" min-width="180">
                <template #default="{ row }">
                  {{ row.spaceName || spaceNames.get(row.spaceId) || row.spaceId }}
                </template>
              </el-table-column>
              <el-table-column prop="opCode" label="操作" min-width="140" />
              <el-table-column prop="status" label="状态" width="110" />
              <el-table-column prop="caller" label="调用方" width="150" />
              <el-table-column label="操作" width="140">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    @click="
                      router.push(`/spaces/${row.spaceId}/executions/${row.id}/trace`)
                    "
                  >
                    查看追溯
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`审计 ${audit.length}`" name="audit">
            <el-table :data="audit" row-key="id">
              <el-table-column prop="action" label="动作" min-width="160" />
              <el-table-column label="所属本体" min-width="180">
                <template #default="{ row }">
                  {{ row.spaceName || spaceNames.get(row.spaceId) || row.spaceId }}
                </template>
              </el-table-column>
              <el-table-column prop="actorName" label="操作人" width="130" />
              <el-table-column prop="subjectType" label="对象类型" width="110" />
              <el-table-column prop="result" label="结果" width="90" />
              <el-table-column prop="createdAt" label="时间" width="190" />
              <el-table-column label="操作" width="120">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    @click="router.push(`/trace?spaceId=${row.spaceId}&tab=audit`)"
                  >
                    进入审计
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </AsyncState>
    </section>
  </section>
</template>
<style scoped>
.global-filter {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 12px;
}
.global-filter .el-select {
  width: 220px;
}
.global-filter .el-input {
  flex: 1 1 260px;
}
</style>
