import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@onto/api-client'
import { useDraftRevision } from './useDraftRevision'

describe('编辑表单的修订不变量', () => {
  it('旧表单409后即使共享修订更新也不会再次写入', async () => {
    const draft = useDraftRevision()
    draft.start(5)
    const request = vi.fn().mockRejectedValue(new ApiError('DRAFT_REV_CONFLICT', '草稿已更新', 409))
    await expect(draft.submit(request)).rejects.toMatchObject({ code: 'DRAFT_REV_CONFLICT' })
    await expect(draft.submit(request)).rejects.toMatchObject({ code: 'DRAFT_FORM_STALE' })
    expect(request).toHaveBeenCalledTimes(1)
    expect(request).toHaveBeenCalledWith(5)
    draft.start(6)
    request.mockResolvedValue(undefined)
    await draft.submit(request)
    expect(request).toHaveBeenLastCalledWith(6)
  })
})
