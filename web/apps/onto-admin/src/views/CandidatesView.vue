<script setup lang="ts">
/** 候选冻结和试执行入口；候选选择改变立即清理旧操作与结果，试执行期间锁定选择防串数据。 */
import { computed, onMounted, ref } from 'vue'
import type { Candidate, Space } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useWorkspace } from '../stores/workspace'
import { useSession } from '../stores/session'
import { useResource } from '../composables/useResource'
import { useMutation, confirmAction } from '../composables/useMutation'
import FrozenOperationTrialPanel from '../features/p1/FrozenOperationTrialPanel.vue'
import CandidateChecksPanel from '../features/p1/CandidateChecksPanel.vue'
const workspace = useWorkspace(),
  api = useSession().api,
  candidates = useResource<Candidate[]>([])
const freeze = useMutation(),
  trialBusy = ref(false),
  selectedId = ref('')
const selected = computed(() => candidates.data.value.find((item) => item.id === selectedId.value))
async function load() {
  const id = workspace.space?.id
  if (!id) return
  let latestSpace: Space | undefined
  const result = await candidates.load(async () => {
    const [items, current] = await Promise.all([
      api.get<Candidate[]>(`/spaces/${id}/candidates`),
      api.get<Space>(`/spaces/${id}`),
    ])
    latestSpace = current
    return items
  })
  if (result && latestSpace) workspace.refreshed(latestSpace)
  if (!candidates.data.value.some((item) => item.id === selectedId.value))
    selectedId.value = candidates.data.value[0]?.id || ''
}
async function createCandidate() {
  const space = workspace.space
  if (!space || !workspace.canWrite) return
  if (
    !(await confirmAction(
      `将草稿 r${space.draftRev} 冻结为不可变候选？之后的草稿修改不会改变此候选。`,
    ))
  )
    return
  await freeze.run(async () => {
    const result = await api.post<Candidate>(`/spaces/${space.id}/candidates`, {
      expectedDraftRev: space.draftRev,
    })
    await load()
    selectedId.value = result.id
  })
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>候选与试执行</h1>
      <p class="muted">冻结模型与操作，在固定内容上验证查询结果。</p>
    </div>
    <div class="row">
      <el-button :disabled="trialBusy || freeze.pending.value" @click="load">刷新候选</el-button>
      <el-button
        v-if="workspace.canWrite"
        type="primary"
        :loading="freeze.pending.value"
        @click="createCandidate"
      >
        冻结当前草稿 r{{ workspace.space?.draftRev }}
      </el-button>
    </div>
  </div>
  <p v-if="freeze.error.value" class="error-text" role="alert">{{ freeze.error.value }}</p>
  <AsyncState
    :loading="candidates.loading.value"
    :error="candidates.error.value"
    :empty="!candidates.data.value.length"
    empty-text="还没有候选。先建立模型与操作，再冻结当前草稿。"
    @retry="load"
  >
    <div class="candidate-grid">
      <section class="panel candidate-list">
        <h2>冻结候选</h2>
        <p class="muted">此入口始终为候选预览（preview）；正式消费请使用已发布版本。</p>
        <button
          v-for="candidate in candidates.data.value"
          :key="candidate.id"
          type="button"
          class="candidate-button"
          :class="{ selected: candidate.id === selectedId }"
          :aria-pressed="candidate.id === selectedId"
          :disabled="trialBusy"
          @click="selectedId = candidate.id"
        >
          <span class="row spread">
            <b>草稿 r{{ candidate.draftRev }}</b>
            <span class="pill">{{ candidate.status }}</span>
          </span>
          <span class="candidate-date">
            {{ new Date(candidate.createdAt).toLocaleString('zh-CN') }}
          </span>
          <span class="mono candidate-hash">{{ candidate.sha256.slice(0, 20) }}…</span>
        </button>
      </section>
      <section v-if="selected" class="panel stack">
        <FrozenOperationTrialPanel :candidate="selected" @busy="trialBusy = $event" />
        <CandidateChecksPanel :key="selected.id" :candidate="selected" />
      </section>
    </div>
  </AsyncState>
</template>
<style scoped>
.candidate-grid {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}
.candidate-list {
  padding: 20px 16px;
}
.candidate-list > p {
  font-size: 11px;
}
.candidate-button {
  display: block;
  width: 100%;
  text-align: left;
  padding: 16px;
  margin: 10px 0;
  background: white;
  border: 1px solid var(--line);
  border-radius: 6px;
  color: var(--text);
}
.candidate-button.selected {
  border-color: var(--primary);
  background: #f1f6ff;
  box-shadow: inset 3px 0 var(--primary);
}
.candidate-button b {
  font-size: 14px;
}
.candidate-date,
.candidate-hash {
  display: block;
  color: var(--muted);
  font-size: 11px;
  margin-top: 10px;
}
.candidate-hash {
  margin-top: 6px;
}
.candidate-button:disabled {
  opacity: 0.65;
}
@media (max-width: 1000px) {
  .candidate-grid {
    grid-template-columns: 230px minmax(0, 1fr);
  }
}
@media (max-width: 800px) {
  .candidate-grid {
    grid-template-columns: 1fr;
  }
  .candidate-list {
    max-height: 320px;
    overflow: auto;
  }
}
</style>
