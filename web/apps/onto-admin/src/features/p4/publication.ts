import type { GateKind, Publication } from '../../../../../packages/api-client/src/release'

export const gateLabels: Record<GateKind, string> = {
  CONFLICT: '冲突',
  CONSISTENCY: '一致性',
  CONTRACT: '操作契约',
  TEST: '验收测试',
  REVIEW: '人工复核',
}
export const gateKinds = Object.keys(gateLabels) as GateKind[]
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
