import type { GateKind, Publication } from '../../../../../packages/api-client/src/release'

export const gateLabels: Record<GateKind, string> = {
  CONFLICT: '冲突',
  CONSISTENCY: '一致性',
  CONTRACT: '操作契约',
  TEST: '验收测试',
  REVIEW: '人工复核',
}
export const gateKinds = Object.keys(gateLabels) as GateKind[]

export const publicationStatusLabels: Record<string, string> = {
  FREEZING: '冻结中',
  VALIDATING: '机器检查中',
  TESTING: '验收测试中',
  REVIEWING: '待人工复核',
  READY_TO_COMMIT: '待提交发布',
  COMMITTED: '已提交发布',
  CANCELLED: '已取消',
  FAILED: '检查未通过',
}

export const checkConclusionLabels: Record<string, string> = {
  PASSED: '通过',
  FAILED: '未通过',
  NOT_CHECKED: '未校验',
}
/** 豁免只能覆盖真实超子集的一致性结论，不能覆盖守卫、超时或执行失败。 */
export function canWaiveConsistency(value: Publication) {
  return value.checks.some(
    (check) =>
      check.kind === 'CONSISTENCY' &&
      check.candidateSha256 === value.candidateSha256 &&
      check.conclusion === 'NOT_CHECKED' &&
      check.reason === 'SUBSET_EXCEEDED',
  )
}
export function gatePassed(value: Publication, kind: GateKind) {
  const check = value.checks.find(
    (item) => item.kind === kind && item.candidateSha256 === value.candidateSha256,
  )
  return (
    check?.conclusion === 'PASSED' ||
    (kind === 'CONSISTENCY' &&
      canWaiveConsistency(value) &&
      value.waivers.some((item) => item.candidateSha256 === value.candidateSha256))
  )
}
export function allArtifactsReady(value: Publication) {
  return (['BUNDLE', 'MANIFEST', 'CONTEXT_PACK', 'KNOWLEDGE_INDEX'] as const).every((kind) =>
    value.artifacts.some(
      (item) =>
        item.kind === kind &&
        item.ready &&
        !!item.sha256 &&
        item.size !== null &&
        item.size >= 0 &&
        (kind !== 'BUNDLE' || item.sha256 === value.candidateSha256),
    ),
  )
}
