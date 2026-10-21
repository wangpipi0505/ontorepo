import { ApiClient, ApiError, type Space } from '@onto/api-client'

/** 读模型前后检查空间修订；并发变化时拒绝恢复写权限，不能把旧实体配上新修订。 */
export async function readDraftSnapshot<T>(
  api: Pick<ApiClient, 'get'>,
  spaceId: string,
  read: () => Promise<T>,
) {
  const before = await api.get<Space>(`/spaces/${spaceId}`)
  const items = await read()
  const after = await api.get<Space>(`/spaces/${spaceId}`)
  if (before.draftRev !== after.draftRev)
    throw new ApiError(
      'DRAFT_READ_CONFLICT',
      '读取期间草稿发生变化，请重新刷新以载入一致内容。',
      409,
    )
  return { items, space: after }
}
