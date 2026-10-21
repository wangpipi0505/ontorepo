<script setup lang="ts">
/** 固定候选身份贯穿检查、复核和提交；刷新只读取事实，不更新提交修订。 */
import { computed, ref, watch } from 'vue'
import { ApiError, type CandidateTests } from '@onto/api-client'
import type {
  PublicationCandidate,
  Publication,
  Release,
  ReleaseDiff,
} from '../../../../../packages/api-client/src/release'
import { useWorkspace } from '../../stores/workspace'
import { useSession } from '../../stores/session'
import { useResource } from '../../composables/useResource'
import { useMutation } from '../../composables/useMutation'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import ArtifactList from './ArtifactList.vue'
import ProjectionDiff from './ProjectionDiff.vue'
import CandidateTestsPanel from '../p1/CandidateTestsPanel.vue'
import {
  allArtifactsReady,
  canWaiveConsistency,
  gateKinds,
  gateLabels,
  gatePassed,
} from './publication'
import './p4.css'
const props = defineProps<{ candidate: PublicationCandidate }>()
const emit = defineEmits<{
  committed: [release: Release]
  busy: [value: boolean]
  'publication-updated': [value: Publication]
}>()
const workspace = useWorkspace(),
  api = useSession().api,
  mutation = useMutation()
const state = useResource<Publication | null>(null),
  diff = useResource<ReleaseDiff | null>(null)
const testsBusy = ref(false)
const busy = computed(() => mutation.pending.value || testsBusy.value)
const reason = ref(''),
  reviewed = ref(false),
  tests = ref<CandidateTests | null>(null),
  locked = ref(false)
let generation = 0
const publication = computed(() => state.data.value)
const owner = computed(() => workspace.space?.role === 'OWNER' && useSession().has('release.write'))
const legacy = computed(() => !publication.value?.preReleaseId || !publication.value.preVersionNo)
const writable = computed(
  () =>
    workspace.canWrite &&
    !locked.value &&
    props.candidate.draftRev === workspace.space?.draftRev &&
    !busy.value,
)
const actionable = computed(
  () =>
    writable.value &&
    !!publication.value &&
    !legacy.value &&
    !['FREEZING', 'COMMITTED', 'CANCELLED'].includes(publication.value.status),
)
const completedTests = computed(
  () => !tests.value || (tests.value.passed && tests.value.results.length > 0),
)
const machinePassed = computed(
  () =>
    completedTests.value &&
    !!publication.value &&
    gateKinds
      .filter((kind) => kind !== 'REVIEW')
      .every((kind) => gatePassed(publication.value!, kind)),
)
const allPassed = computed(
  () =>
    completedTests.value &&
    !!publication.value &&
    gateKinds.every((kind) => gatePassed(publication.value!, kind)),
)
const prepareAllowed = computed(
  () =>
    owner.value &&
    actionable.value &&
    allPassed.value &&
    ['REVIEWING', 'READY_TO_COMMIT'].includes(publication.value!.status),
)
const commitAllowed = computed(
  () =>
    prepareAllowed.value &&
    publication.value?.status === 'READY_TO_COMMIT' &&
    allArtifactsReady(publication.value),
)
watch(busy, (value) => emit('busy', value), { flush: 'sync' })
async function acceptTests(result: CandidateTests) {
  tests.value = result
  await load()
}
function verify(value: Publication) {
  if (
    value.candidateId !== props.candidate.id ||
    value.candidateSha256 !== props.candidate.sha256 ||
    value.draftRev !== props.candidate.draftRev
  )
    throw new ApiError(
      'CANDIDATE_IDENTITY_MISMATCH',
      '响应不属于当前候选摘要与修订，请重新选择候选。',
      409,
    )
  return value
}
async function load() {
  if (mutation.pending.value) return
  const id = props.candidate.id,
    ticket = generation,
    context = workspace.captureContext()
  const result = await state.load(async () => {
    const value = await api.get<Publication>(`/candidates/${id}/publication`)
    if (ticket !== generation || !workspace.isCurrentContext(context)) return null
    return verify(value)
  })
  // useResource 只返回赢得竞态的结果；同空间重新进入也须重新核对请求上下文。
  if (result && ticket === generation && workspace.isCurrentContext(context))
    emit('publication-updated', result)
}
function accept(value: Publication) {
  const verified = verify(value)
  state.data.value = verified
  emit('publication-updated', verified)
}
function loadDiff() {
  return diff.load(() => api.get<ReleaseDiff>(`/candidates/${props.candidate.id}/diff`))
}
watch(
  () => `${props.candidate.id}:${props.candidate.sha256}:${props.candidate.draftRev}`,
  () => {
    generation++
    state.reset()
    diff.reset()
    reason.value = ''
    reviewed.value = false
    tests.value = null
    locked.value = false
    void load()
    void loadDiff()
  },
  { immediate: true },
)
async function action(kind: 'validate' | 'review' | 'prepare' | 'commit' | 'cancel' | 'waivers') {
  const value = publication.value
  if (!value || !actionable.value) return
  if (kind === 'validate' && !['VALIDATING', 'TESTING', 'FAILED'].includes(value.status)) return
  if (kind === 'review' && value.status !== 'REVIEWING') return
  if (['review', 'prepare', 'commit', 'cancel', 'waivers'].includes(kind) && !owner.value) return
  if ((kind === 'prepare' && !prepareAllowed.value) || (kind === 'commit' && !commitAllowed.value))
    return
  if (kind === 'review' && (!machinePassed.value || !reviewed.value || !diff.data.value)) return
  if (kind === 'waivers' && !canWaiveConsistency(value)) return
  if (['review', 'cancel', 'waivers'].includes(kind) && !reason.value.trim()) {
    mutation.error.value = '请填写明确的操作理由。'
    return
  }
  const id = props.candidate.id,
    sha = props.candidate.sha256,
    rev = props.candidate.draftRev,
    ticket = generation
  const body =
    kind === 'commit'
      ? { expectedDraftRev: rev, expectedSha256: sha }
      : kind === 'waivers'
        ? { kind: 'SUBSET_EXCEEDED', reason: reason.value.trim() }
        : kind === 'validate'
          ? {}
          : {
              expectedDraftRev: rev,
              candidateSha256: sha,
              ...(['review', 'cancel'].includes(kind) ? { reason: reason.value.trim() } : {}),
            }
  await mutation.run(async ({ isCurrent }) => {
    try {
      const response = await api.post<Publication | Release>(`/candidates/${id}/${kind}`, body)
      if (!isCurrent() || ticket !== generation) return
      if (kind === 'commit') {
        const release = response as Release
        if (release.candidateId !== id || release.bundleSha256 !== sha)
          throw new Error('发布响应与当前冻结候选不符，请重新读取版本。')
        emit('committed', release)
      } else if (kind !== 'waivers') {
        accept(response as Publication)
        return
      }
      const latest = await api.get<Publication>(`/candidates/${id}/publication`)
      if (isCurrent() && ticket === generation) accept(latest)
    } catch (cause) {
      if (isCurrent() && ticket === generation && cause instanceof ApiError && cause.status === 409)
        locked.value = true
      throw cause
    }
  })
}
</script>
<template>
  <section class="p4-page panel stack">
    <div class="row spread">
      <h2>发布向导 · r{{ candidate.draftRev }}</h2>
      <el-button :disabled="mutation.pending.value" @click="load">刷新发布状态</el-button>
    </div>
    <p class="identity">
      候选 {{ candidate.id }}
      <br />
      SHA-256 {{ candidate.sha256 }}
    </p>
    <p v-if="!writable && !mutation.pending.value" class="readonly-note">
      当前候选或草稿已过期，或当前账号仅可查看。原输入保留；请重新读取草稿并冻结候选。
    </p>
    <p v-if="mutation.error.value" class="error-text" role="alert">{{ mutation.error.value }}</p>
    <AsyncState :loading="state.loading.value" :error="state.error.value" @retry="load">
      <template v-if="publication">
        <div class="row">
          <el-tag>{{ publication.status }}</el-tag>
          <b v-if="publication.preVersionNo">预分配 v{{ publication.preVersionNo }}</b>
        </div>
        <p v-if="legacy" class="readonly-note">
          此旧候选继续兼容本地试验；请重新冻结当前草稿以获得正式发布能力。
        </p>
        <p v-if="publication.capability === 'MODEL_ONLY'" class="notice">
          本候选仅含模型（无助手声明），发布后不生成服务端点；契约门禁按 0 个 manifest
          通过，消费方无法调用。
        </p>
        <p v-if="publication.releaseId" class="notice">已发布版本：{{ publication.releaseId }}</p>
        <div class="gates" aria-label="五项发布门禁">
          <article
            v-for="kind in gateKinds"
            :key="kind"
            class="record gate"
            :data-passed="gatePassed(publication, kind)"
          >
            <h3>{{ gateLabels[kind] }}</h3>
            <b>
              {{
                publication.checks.find((check) => check.kind === kind)?.conclusion ?? '尚未执行'
              }}
            </b>
            <p class="muted">
              {{
                publication.checks.find((check) => check.kind === kind)?.reason ?? '等待服务端检查'
              }}
            </p>
            <details v-if="publication.checks.some((check) => check.kind === kind)">
              <summary>证据与时间</summary>
              <pre>{{
                JSON.stringify(
                  publication.checks.find((check) => check.kind === kind),
                  null,
                  2,
                )
              }}</pre>
            </details>
          </article>
        </div>
        <div class="row">
          <el-button
            :disabled="
              !actionable || !['VALIDATING', 'TESTING', 'FAILED'].includes(publication.status)
            "
            @click="action('validate')"
          >
            运行三项机器检查
          </el-button>
        </div>
        <CandidateTestsPanel
          :previous-result="tests"
          :candidate="candidate"
          :enabled="
            writable &&
            !!publication &&
            !legacy &&
            !['FREEZING', 'COMMITTED', 'CANCELLED', 'VOID', 'READY_TO_COMMIT'].includes(
              publication.status,
            ) &&
            gatePassed(publication, 'CONTRACT')
          "
          @busy="testsBusy = $event"
          @conflict="locked = true"
          @completed="acceptTests"
        />
        <details open>
          <summary>冻结内容差异</summary>
          <AsyncState :loading="diff.loading.value" :error="diff.error.value" @retry="loadDiff">
            <ProjectionDiff v-if="diff.data.value" :diff="diff.data.value" />
          </AsyncState>
        </details>
        <template v-if="owner">
          <label>
            复核 / 取消 / 豁免理由
            <textarea
              v-model="reason"
              data-testid="publication-reason"
              rows="3"
              :disabled="mutation.pending.value"
            />
          </label>
          <label class="check-label">
            <input v-model="reviewed" type="checkbox" :disabled="!actionable" />
            我已核对冻结差异与全部机器门禁证据
          </label>
          <div class="row">
            <el-button
              :disabled="
                !actionable ||
                publication.status !== 'REVIEWING' ||
                !machinePassed ||
                !reviewed ||
                !reason.trim() ||
                !diff.data.value
              "
              @click="action('review')"
            >
              确认人工复核
            </el-button>
            <el-button
              v-if="canWaiveConsistency(publication)"
              type="warning"
              :disabled="!actionable || !reason.trim()"
              @click="action('waivers')"
            >
              确认超范围豁免
            </el-button>
            <el-button
              type="danger"
              :disabled="!actionable || !reason.trim()"
              @click="action('cancel')"
            >
              取消此候选
            </el-button>
          </div>
        </template>
        <p v-for="waiver in publication.waivers" :key="waiver.createdAt" class="readonly-note">
          一致性豁免：{{ waiver.reason }} · {{ waiver.actorId }} · {{ waiver.createdAt }}
        </p>
        <p v-if="publication.review" class="muted">
          复核：{{ publication.review.actorId }} · {{ publication.review.reason }} ·
          {{ publication.review.reviewedAt }}
        </p>
        <ArtifactList :artifacts="publication.artifacts" />
        <p class="notice">
          先准备并核验全部制品，达到 READY_TO_COMMIT
          后再提交。制品失败可重试准备；服务端提交时仍会复核摘要、权限、修订和五门禁。
        </p>
        <div v-if="owner" class="row">
          <el-button
            :disabled="!prepareAllowed"
            :loading="mutation.pending.value"
            @click="action('prepare')"
          >
            准备发布制品
          </el-button>
          <el-button type="primary" :disabled="!commitAllowed" @click="action('commit')">
            提交正式发布
          </el-button>
        </div>
      </template>
    </AsyncState>
  </section>
</template>
