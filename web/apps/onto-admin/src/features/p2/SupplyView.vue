<script setup lang="ts">
/** 供给页独立于 P1 冻结/试执行页；切换候选销毁旧面板，避免异步结果串候选。 */
import { computed, onMounted, ref } from 'vue'
import type { Candidate } from '@onto/api-client'
import { useWorkspace } from '../../stores/workspace'
import { useSession } from '../../stores/session'
import { useResource } from '../../composables/useResource'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import CandidateSupplyPanel from './CandidateSupplyPanel.vue'
import InternalCallPanel from './InternalCallPanel.vue'
const workspace = useWorkspace(),
  api = useSession().api,
  candidates = useResource<Candidate[]>([])
const id = ref(''),
  selected = computed(() => candidates.data.value.find((item) => item.id === id.value))
async function load() {
  const spaceId = workspace.space?.id
  if (!spaceId) return
  await candidates.load(() => api.get<Candidate[]>(`/spaces/${spaceId}/candidates`))
  if (!selected.value) id.value = candidates.data.value[0]?.id || ''
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>候选供给</h1>
      <p class="muted">查看冻结契约与知识包，并验证内部候选调用。</p>
    </div>
    <el-button @click="load">刷新候选</el-button>
  </div>
  <AsyncState
    :loading="candidates.loading.value"
    :error="candidates.error.value"
    :empty="!candidates.data.value.length"
    empty-text="暂无候选。先在候选与试执行页冻结当前草稿。"
    @retry="load"
  >
    <div class="stack">
      <section class="panel">
        <el-form label-position="top">
          <el-form-item label="冻结候选">
            <el-select v-model="id">
              <el-option
                v-for="candidate in candidates.data.value"
                :key="candidate.id"
                :value="candidate.id"
                :label="`草稿 r${candidate.draftRev} · ${candidate.sha256.slice(0, 16)}`"
              />
            </el-select>
          </el-form-item>
        </el-form>
        <p class="muted">
          旧格式候选保留原始字节，仅支持原有模型演示；不能取得 CONTRACT 资格或供内部服务消费。
        </p>
      </section>
      <template v-if="selected">
        <CandidateSupplyPanel :key="`${selected.id}-supply`" :candidate="selected" />
        <InternalCallPanel :key="`${selected.id}-call`" :candidate="selected" />
      </template>
    </div>
  </AsyncState>
</template>
