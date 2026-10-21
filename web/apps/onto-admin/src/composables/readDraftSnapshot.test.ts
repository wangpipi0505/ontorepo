import { describe, expect, it, vi } from 'vitest'
import { ApiClient } from '@onto/api-client'
import { readDraftSnapshot } from './readDraftSnapshot'

describe('完整草稿刷新的一致性', () => {
  it('读实体前后修订变化时拒绝将旧实体绑定新修订', async () => {
    const api = new ApiClient(
      () => null,
      () => {},
    )
    vi.spyOn(api, 'get')
      .mockResolvedValueOnce({ draftRev: 5 })
      .mockResolvedValueOnce({ draftRev: 6 })
    await expect(
      readDraftSnapshot(api, 'space', async () => [{ name: '旧类名' }]),
    ).rejects.toMatchObject({ code: 'DRAFT_READ_CONFLICT' })
  })
})
