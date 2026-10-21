<script setup lang="ts">
/** 冻结与发布页面独立于既有候选试执行页，旧候选仍可回原入口试验。 */
import { ref, watch } from 'vue'
import type { Space } from '@onto/api-client'
import type {
  Publication,
  PublicationCandidate,
} from '../../../../../packages/api-client/src/release'
import { useWorkspace } from '../../stores/workspace'
import { useSession } from '../../stores/session'
import { useResource } from '../../composables/useResource'
import { useMutation, confirmAction } from '../../composables/useMutation'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import PublicationPanel from './PublicationPanel.vue'
import './p4.css'
const workspace = useWorkspace(),
  api = useSession().api,
  mutation = useMutation()
const state = useResource<PublicationCandidate[]>([]),
  selected = ref(''),
  opened = ref(0),
  publicationBusy = ref(false)
/** 仅同步同一候选身份的真实状态，不重读草稿或重挂载详情，保留人工输入。 */
function syncPublication(value: Publication) {
  if (selected.value !== value.candidateId) return
  state.data.value = state.data.value.map((item) =>
    item.id === value.candidateId &&
    item.spaceId === workspace.space?.id &&
    item.sha256 === value.candidateSha256 &&
    item.draftRev === value.draftRev &&
    item.status !== value.status
      ? { ...item, status: value.status }
      : item,
  )
}
async function load() {
  const id = workspace.space?.id,
    context = workspace.captureContext()
  if (!id || mutation.pending.value || publicationBusy.value || state.loading.value) return
  const result = await state.load(async () => {
    const [items, space] = await Promise.all([
      api.get<PublicationCandidate[]>(`/spaces/${id}/candidates`),
      api.get<Space>(`/spaces/${id}`),
    ])
    if (workspace.isCurrentContext(context)) workspace.refreshed(space)
    return items
  })
  if (result && workspace.isCurrentContext(context)) {
    if (!result.some((item) => item.id === selected.value)) selected.value = result[0]?.id ?? ''
    opened.value++
  }
}
watch(
  () => workspace.space?.id,
  () => {
    selected.value = ''
    state.reset()
    void load()
  },
  { immediate: true },
)
async function freeze() {
  const space = workspace.space,
    context = workspace.captureContext()
  if (
    !space ||
    !workspace.canWriteFor('candidate.write') ||
    publicationBusy.value ||
    state.loading.value
  )
    return
  const rev = space.draftRev
  if (
    !(await confirmAction(
      `将当前草稿 r${rev} 冻结为发布候选？预分配版本号在取消或失败后不会回收。`,
    )) ||
    !workspace.isCurrentContext(context)
  )
    return
  let created: PublicationCandidate | undefined
  if (
    await mutation.run(async ({ isCurrent }) => {
      const value = await api.post<PublicationCandidate>(`/spaces/${space.id}/candidates`, {
        expectedDraftRev: rev,
      })
      if (isCurrent()) created = value
    })
  ) {
    await load()
    if (created) selected.value = created.id
  }
}
</script>
<template>
  <div class="p4-page stack">
    <div class="page-heading">
      <div>
        <h1>候选发布</h1>
        <p class="muted">从冻结草稿到正式版本，逐项查看发布资格与制品证据。</p>
      </div>
      <div class="row">
        <el-button
          :disabled="mutation.pending.value || publicationBusy || state.loading.value"
          @click="load"
        >
          重新读取候选与草稿
        </el-button>
        <el-button
          v-if="workspace.canWriteFor('candidate.write')"
          type="primary"
          :disabled="publicationBusy || state.loading.value"
          :loading="mutation.pending.value"
          @click="freeze"
        >
          冻结发布候选 r{{ workspace.space?.draftRev }}
        </el-button>
      </div>
    </div>
    <p v-if="mutation.error.value" class="error-text" role="alert">{{ mutation.error.value }}</p>
    <AsyncState
      :loading="state.loading.value"
      :error="state.error.value"
      :empty="!state.data.value.length"
      empty-text="尚无候选。完成模型、映射和验收用例后冻结当前草稿。"
      @retry="load"
    >
      <div class="split">
        <section class="panel stack">
          <h2>冻结候选</h2>
          <button
            v-for="item in state.data.value"
            :key="item.id"
            class="record selector"
            :aria-pressed="selected === item.id"
            :disabled="publicationBusy || mutation.pending.value"
            @click="selected = item.id"
          >
            <b>草稿 r{{ item.draftRev }}</b>
            <p class="muted">
              {{ item.status }} · {{ item.preVersionNo ? `v${item.preVersionNo}` : '历史候选' }}
            </p>
            <span class="identity">{{ item.sha256 }}</span>
          </button>
        </section>
        <PublicationPanel
          v-if="state.data.value.find((item) => item.id === selected)"
          :key="`${selected}:${opened}`"
          :candidate="state.data.value.find((item) => item.id === selected)!"
          @busy="publicationBusy = $event"
          @publication-updated="syncPublication"
        />
      </div>
    </AsyncState>
  </div>
</template>
