<script setup lang="ts">
/** 仅从当前空间读取候选列表；执行中的父组件会锁定选择，避免将响应展示到另一份冻结内容。 */
import { computed, onMounted, ref } from 'vue'
import type { PublicationCandidate } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useResource } from '../../composables/useResource'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
defineProps<{ busy: boolean }>()
const candidates = useResource<PublicationCandidate[]>([]),
  selectedId = ref(''),
  workspace = useWorkspace(),
  api = useSession().api
const selected = computed(() =>
  candidates.data.value.find((candidate) => candidate.id === selectedId.value),
)
async function load() {
  const id = workspace.space?.id
  if (!id) return
  const result = await candidates.load(() =>
    api.get<PublicationCandidate[]>(`/spaces/${id}/candidates`),
  )
  if (result && !result.some((candidate) => candidate.id === selectedId.value))
    selectedId.value = ''
}
onMounted(load)
</script>
<template>
  <section class="stack">
    <div class="row spread">
      <h2>选择冻结候选</h2>
      <el-button :disabled="busy" @click="load">刷新候选</el-button>
    </div>
    <AsyncState
      :loading="candidates.loading.value"
      :error="candidates.error.value"
      :empty="!candidates.data.value.length"
      empty-text="暂无候选，请先前往“候选与试执行”冻结草稿。"
      @retry="load"
    >
      <el-select
        v-model="selectedId"
        placeholder="选择需要验证的冻结候选"
        :disabled="busy"
        aria-label="冻结候选"
      >
        <el-option
          v-for="candidate in candidates.data.value"
          :key="candidate.id"
          :value="candidate.id"
          :label="`r${candidate.draftRev} · ${candidate.status} · ${candidate.sha256.slice(0, 16)}`"
        />
      </el-select>
      <slot v-if="selected" :candidate="selected" />
    </AsyncState>
  </section>
</template>
