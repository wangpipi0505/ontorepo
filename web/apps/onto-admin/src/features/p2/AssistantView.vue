<script setup lang="ts">
/** 操作清单仅供选择；编辑基线另从该操作声明的一致性读取建立。 */
import { onMounted, ref, watch } from 'vue'
import type { AssistantDeclaration, Operation, Space } from '@onto/api-client'
import { useRoute } from 'vue-router'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import { useResource } from '../../composables/useResource'
import { useDraftDocument } from './useDraftDocument'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import AssistantForm from './AssistantForm.vue'
const api = useSession().api,
  workspace = useWorkspace(),
  route = useRoute()
const operations = useResource<Operation[]>([]),
  selected = ref('')
const editor = useDraftDocument((spaceId) =>
  api.get<AssistantDeclaration>(`/operations/${selected.value}/assistant?spaceId=${spaceId}`),
)
async function loadOperations() {
  const id = workspace.space?.id
  if (!id) return
  await operations.load(() => api.get<Operation[]>(`/operations?spaceId=${id}`))
}
async function refresh() {
  await loadOperations()
  if (selected.value) await editor.load()
}
watch(selected, () => {
  editor.resource.reset()
  if (selected.value) void editor.load()
})
async function save(value: Omit<AssistantDeclaration, 'operationId'>) {
  const id = workspace.space?.id,
    operationId = selected.value
  if (!id || !operationId) return
  if (
    await editor.save((expectedDraftRev) =>
      api.put<Space>(`/operations/${operationId}/assistant`, {
        ...value,
        spaceId: id,
        expectedDraftRev,
      }),
    )
  )
    await editor.load()
}
onMounted(async () => {
  await loadOperations()
  const requested = route?.query.operationId
  if (typeof requested === 'string' && operations.data.value.some((item) => item.id === requested))
    selected.value = requested
})
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>助手声明</h1>
      <p class="muted">为模型操作定义输入、输出、必需守卫与执行属性。</p>
    </div>
    <el-button :disabled="editor.mutation.pending.value" @click="refresh">刷新声明</el-button>
  </div>
  <section class="panel stack">
    <AsyncState
      :loading="operations.loading.value"
      :error="operations.error.value"
      @retry="loadOperations"
    >
      <el-form label-position="top">
        <el-form-item label="草稿操作">
          <el-select
            v-model="selected"
            :disabled="editor.mutation.pending.value"
            placeholder="选择操作以维护声明"
          >
            <el-option
              v-for="item in operations.data.value"
              :key="item.id"
              :value="item.id"
              :label="`${item.name}（${item.code}）`"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <p v-if="!operations.data.value.length" class="muted">尚无操作。请先在模型操作页创建。</p>
    </AsyncState>
    <AsyncState
      v-if="selected"
      :loading="editor.resource.loading.value"
      :error="editor.resource.error.value"
      @retry="editor.load"
    >
      <AssistantForm
        v-if="editor.resource.data.value"
        :value="editor.resource.data.value.items"
        :can-write="workspace.canWrite"
        :pending="editor.mutation.pending.value"
        :stale="editor.draft.stale.value"
        :error="editor.mutation.error.value"
        @save="save"
      />
    </AsyncState>
  </section>
</template>
