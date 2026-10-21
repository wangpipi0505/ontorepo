<script setup lang="ts">
/** 草稿操作列表与语句预览；编辑提交使用空间修订，删除前确认，候选中的旧操作不随草稿删除。 */
import { onMounted, ref } from 'vue'
import type { Operation, Space } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useWorkspace } from '../stores/workspace'
import { useSession } from '../stores/session'
import { readDraftSnapshot } from '../composables/readDraftSnapshot'
import { useResource } from '../composables/useResource'
import { useMutation, confirmAction } from '../composables/useMutation'
import { useEntityFocus } from '../composables/useEntityFocus'
import OperationDialog from '../components/OperationDialog.vue'
const workspace = useWorkspace(),
  api = useSession().api,
  operations = useResource<Operation[]>([]),
  mutation = useMutation()
const focus = useEntityFocus(operations.data, operations.loading)
const dialog = ref(false),
  selected = ref<Operation | null>(null),
  preview = ref<Operation | null>(null)
const kindLabels = {
  OBJECT_QUERY: '对象查询',
  RELATION_QUERY: '关系查询',
  RULE_CHECK: '规则检查',
  SCORING: '评分',
}
async function load() {
  const id = workspace.space?.id
  if (!id) return
  if (workspace.stale) dialog.value = false
  let latestSpace: Space | undefined
  const result = await operations.load(async () => {
    const snapshot = await readDraftSnapshot(api, id, () =>
      api.get<Operation[]>(`/operations?spaceId=${id}`),
    )
    latestSpace = snapshot.space
    return snapshot.items
  })
  if (result && latestSpace) workspace.refreshed(latestSpace)
}
function edit(item: Operation | null) {
  if (!workspace.canWrite) return
  selected.value = item
  dialog.value = true
}
async function remove(item: Operation) {
  const space = workspace.space,
    context = workspace.captureContext()
  if (!space || !workspace.canWrite) return
  if (await confirmAction(`删除草稿操作“${item.name}”？已冻结的候选内容仍会保留。`)) {
    if (!workspace.isCurrentContext(context)) return
    await mutation.run(async ({ isCurrent }) => {
      const next = await api.delete<Space>(
        `/operations/${item.id}?spaceId=${space.id}&expectedDraftRev=${space.draftRev}`,
      )
      if (!isCurrent()) return
      workspace.accept(next)
      await load()
    })
  }
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>模型操作</h1>
      <p class="muted">维护对象查询、关系查询、规则检查和评分，冻结后在候选中验证。</p>
    </div>
    <div class="row">
      <el-button @click="load">刷新操作</el-button>
      <el-button v-if="workspace.canWrite" type="primary" @click="edit(null)">新建操作</el-button>
    </div>
  </div>
  <p v-if="mutation.error.value" class="error-text" role="alert">{{ mutation.error.value }}</p>
  <p v-if="focus.message.value" class="notice" role="status">{{ focus.message.value }}</p>
  <section class="panel">
    <AsyncState
      :loading="operations.loading.value"
      :error="operations.error.value"
      :empty="!operations.data.value.length"
      empty-text="还没有模型操作。新建操作后，可使用内置查询模板。"
      @retry="load"
    >
      <div class="overflow">
        <el-table :data="operations.data.value" row-key="id" :row-class-name="focus.rowClassName">
          <el-table-column prop="name" label="操作名称" min-width="160" />
          <el-table-column prop="code" label="操作编码" min-width="150" />
          <el-table-column label="操作类型" width="120">
            <template #default="{ row }">{{ kindLabels[row.kind as Operation['kind']] }}</template>
          </el-table-column>
          <el-table-column prop="maxRows" label="行数上限" width="100" />
          <el-table-column label="超时" width="100">
            <template #default="{ row }">{{ row.timeoutMs }} ms</template>
          </el-table-column>
          <el-table-column label="操作" min-width="260">
            <template #default="{ row }">
              <el-button link type="primary" @click="preview = row">查看定义</el-button>
              <router-link
                :to="`/spaces/${workspace.space?.id}/assistant?operationId=${row.id}`"
                class="assistant-link"
              >
                助手声明
              </router-link>
              <template v-if="workspace.canWrite">
                <el-button link type="primary" @click="edit(row)">编辑</el-button>
                <el-button
                  link
                  type="danger"
                  :disabled="mutation.pending.value"
                  @click="remove(row)"
                >
                  删除
                </el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </AsyncState>
  </section>
  <div class="muted scope-note">
    查询类操作仅查询候选内的模型；规则检查和评分执行冻结规则与输入。
  </div>
  <OperationDialog v-model="dialog" :item="selected" @saved="load" />
  <el-dialog :model-value="!!preview" :title="preview?.name" width="760px" @close="preview = null">
    <p>类型：{{ preview ? kindLabels[preview.kind] : '' }}</p>
    <pre v-if="preview?.queryTemplate" class="query-preview">{{ preview.queryTemplate }}</pre>
    <dl class="details-grid">
      <dt>策略标识</dt>
      <dd class="mono">{{ preview?.strategyRef || '未绑定' }}</dd>
      <dt>规则标识</dt>
      <dd class="mono">{{ preview?.ruleIds?.join('，') || '未绑定' }}</dd>
    </dl>
  </el-dialog>
</template>
<style scoped>
.scope-note {
  margin: 18px 4px;
  font-size: 12px;
}
.assistant-link {
  margin: 0 10px;
  font-size: 12px;
}
.query-preview {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font:
    13px/1.8 Consolas,
    monospace;
  background: var(--canvas);
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: 6px;
}
</style>
