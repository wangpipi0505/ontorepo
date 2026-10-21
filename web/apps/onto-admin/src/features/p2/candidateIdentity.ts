import { ApiError } from '@onto/api-client'
function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
/** manifest 预览没有发布证据；外层 candidateSha256 才是其冻结内容的标识。 */
export function verifyManifestPreview(meta: unknown) {
  if (
    !record(meta) ||
    meta.preview !== true ||
    meta.releaseId !== null ||
    meta.version !== null ||
    meta.contentDigest !== null ||
    meta.spaceCode !== null
  )
    throw new ApiError('INVALID_PREVIEW_META', '候选预览元数据不符合约定，请重新生成契约。', 409)
}
/** 内部试调用使用实际候选摘要，但 releaseId/version 必须为空，不能伪装正式版本。 */
export function verifyInvocationMeta(meta: unknown, sha: string) {
  if (
    !record(meta) ||
    meta.preview !== true ||
    meta.releaseId !== null ||
    meta.version !== null ||
    meta.contentDigest !== sha
  )
    throw new ApiError(
      'CANDIDATE_HASH_MISMATCH',
      '调用结果的候选摘要或 preview 元数据不符，请重新读取候选。',
      409,
    )
}
