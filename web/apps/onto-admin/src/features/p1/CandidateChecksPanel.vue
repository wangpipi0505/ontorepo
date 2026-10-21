<script setup lang="ts">
/** 只调用候选端点；检查结果必须与当前冻结摘要相符，任何失败都不以草稿内容补齐。 */
import { computed, ref } from 'vue'
import {
  ApiError,
  type Candidate,
  type CandidateChecks,
  type CandidateTests,
  type Waiver,
} from '@onto/api-client'
import { useWorkspace } from '../../stores/workspace'
import { useSession } from '../../stores/session'
import { useMutation } from '../../composables/useMutation'
import { canWaive } from './inputs'
const props = defineProps<{ candidate: Candidate }>(),
  workspace = useWorkspace(),
  api = useSession().api,
  mutation = useMutation()
const checks = ref<CandidateChecks | null>(null),
  tests = ref<CandidateTests | null>(null),
  waiver = ref<Waiver | null>(null),
  reason = ref('')
const allowWaiver = computed(
  () =>
    workspace.canWriteFor('release.write') &&
    props.candidate.draftRev === workspace.space?.draftRev &&
    canWaive(workspace.space?.role || '', checks.value?.checks || []),
)
function verifyHash(hash: string) {
  if (hash !== props.candidate.sha256)
    throw new ApiError(
      'CANDIDATE_HASH_MISMATCH',
      '检查结果摘要与当前候选不符，请重新读取候选。',
      409,
    )
}
async function runChecks() {
  if (!workspace.canWriteFor('candidate.write')) return
  await mutation.run(async () => {
    const result = await api.post<CandidateChecks>(`/candidates/${props.candidate.id}/checks`, {})
    verifyHash(result.candidateSha256)
    checks.value = result
  })
}
async function runTests() {
  if (!workspace.canWriteFor('test.execute')) return
  await mutation.run(async () => {
    const result = await api.post<CandidateTests>(`/candidates/${props.candidate.id}/tests`, {})
    verifyHash(result.candidateSha256)
    tests.value = result
  })
}
async function waive() {
  if (!allowWaiver.value) return
  if (!reason.value.trim()) {
    mutation.error.value = '请输入超范围豁免原因。'
    return
  }
  const ok = await mutation.run(async () => {
    const result = await api.post<Waiver>(`/candidates/${props.candidate.id}/waivers`, {
      kind: 'SUBSET_EXCEEDED',
      reason: reason.value.trim(),
    })
    verifyHash(result.candidateSha256)
    waiver.value = result
  })
  if (ok) await runChecks()
}
</script>
<template>
  <section class="stack checks-panel">
    <div class="row spread">
      <h3>候选检查</h3>
      <div class="row">
        <el-button
          v-if="workspace.canWriteFor('candidate.write')"
          :loading="mutation.pending.value"
          @click="runChecks"
        >
          运行冲突 / 一致性 / 用例门禁
        </el-button>
        <el-button
          v-if="workspace.canWriteFor('test.execute')"
          :disabled="mutation.pending.value"
          @click="runTests"
        >
          单独运行冻结用例
        </el-button>
      </div>
    </div>
    <p class="muted">
      初期门禁结果仅供候选验证；通过不代表正式发布资格。用例从此候选的冻结副本读取。
    </p>
    <p v-if="mutation.error.value" class="error-text" role="alert">{{ mutation.error.value }}</p>
    <template v-if="checks">
      <div class="row">
        <el-tag :type="checks.eligible ? 'success' : 'warning'">
          {{ checks.eligible ? '初期检查通过' : '检查未通过' }}
        </el-tag>
        <span class="mono muted">{{ checks.candidateSha256 }}</span>
      </div>
      <article
        v-for="(check, index) in checks.checks"
        :key="`${check.kind}-${index}`"
        class="check-row"
      >
        <div class="row spread">
          <b>{{ check.kind }}</b>
          <el-tag
            :type="
              ['PASSED', 'CONSISTENT'].includes(check.conclusion)
                ? 'success'
                : check.conclusion === 'NOT_CHECKED'
                  ? 'warning'
                  : 'danger'
            "
          >
            {{ check.conclusion }}
          </el-tag>
        </div>
        <p>{{ check.message }}</p>
        <p class="muted">原因：{{ check.reason || '无' }}</p>
        <details>
          <summary>查看检查详情</summary>
          <pre class="json-details">{{ JSON.stringify(check.details, null, 2) }}</pre>
        </details>
      </article>
    </template>
    <template v-if="tests">
      <h3>冻结用例结果 · {{ tests.passed ? '通过' : '未通过' }}</h3>
      <el-table :data="tests.results">
        <el-table-column prop="name" label="用例" min-width="140" />
        <el-table-column label="结论" width="90">
          <template #default="{ row }">{{ row.passed ? '通过' : '失败' }}</template>
        </el-table-column>
        <el-table-column prop="message" label="说明" min-width="180" />
        <el-table-column prop="executionId" label="执行留痕" min-width="240" />
      </el-table>
    </template>
    <el-form v-if="allowWaiver" label-position="top" @submit.prevent="waive">
      <div class="readonly-note">
        OWNER 可对真实 NOT_CHECKED / SUBSET_EXCEEDED 留痕豁免。不一致、超时和引擎错误不能豁免。
      </div>
      <el-form-item label="超范围豁免原因" required>
        <el-input v-model="reason" type="textarea" :rows="3" />
      </el-form-item>
      <el-button native-type="submit" type="warning" :loading="mutation.pending.value">
        确认超范围豁免
      </el-button>
    </el-form>
    <p v-else-if="checks && candidate.draftRev !== workspace.space?.draftRev" class="muted">
      此候选对应旧修订，不能对当前草稿申请豁免。
    </p>
    <p v-if="waiver" class="readonly-note">
      已记录豁免：{{ waiver.reason }} · 审批人 {{ waiver.approver }} · {{ waiver.id }}
    </p>
  </section>
</template>
<style scoped>
.checks-panel {
  border-top: 1px solid var(--line);
  padding-top: 20px;
}
.check-row {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 14px;
  min-width: 0;
}
.check-row p {
  margin: 10px 0;
}
.json-details {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 12px;
  background: var(--canvas);
  padding: 12px;
}
</style>
