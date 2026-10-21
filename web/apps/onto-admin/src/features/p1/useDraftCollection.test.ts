import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, type EffectScope } from 'vue'
import { setAuthorizedSession } from '../../test/authorizedSession'
import { ApiError, type Space } from '@onto/api-client'
import { ElMessageBox } from 'element-plus'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import { useDraftCollection } from './useDraftCollection'
const space = { id: 's', role: 'OWNER', status: 'ACTIVE', draftRev: 5 } as Space
describe('P1 草稿提交与错误恢复', () => {
  let scope: EffectScope
  beforeEach(() => {
    scope = effectScope()
    setAuthorizedSession()
    useWorkspace().space = space
  })
  afterEach(() => scope.stop())
  it('删除确认期间重读列表，仍使用最初发起删除的修订', async () => {
    const page = scope.run(() => useDraftCollection<{ id: string; name: string }>('/codebooks'))!
    const item = { id: 'book', name: '单位' }
    let revision = 5
    vi.spyOn(useSession().api, 'get').mockImplementation(
      async <T>(path: string): Promise<T> =>
        (path === '/spaces/s' ? { ...space, draftRev: revision } : [item]) as T,
    )
    const remove = vi.spyOn(useSession().api, 'delete').mockResolvedValue({ ...space, draftRev: 7 })
    let finishConfirm!: () => void
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockImplementation(
      () =>
        new Promise((resolve) => {
          finishConfirm = () =>
            resolve('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
        }),
    )
    try {
      await page.load()
      const pending = page.remove(item)
      revision = 6
      await page.load()
      finishConfirm()
      await pending
      expect(remove).toHaveBeenCalledWith('/codebooks/book?spaceId=s&expectedDraftRev=5')
    } finally {
      confirm.mockRestore()
    }
  })
  it('保存携带打开时修订；409保留表单与输入，刷新空间不能解锁旧表单', async () => {
    const page = scope.run(() => useDraftCollection<{ id: string; name: string }>('/codebooks'))!
    const post = vi
      .spyOn(useSession().api, 'post')
      .mockRejectedValue(new ApiError('DRAFT_REV_CONFLICT', '草稿已更新', 409))
    page.edit(null)
    const body = { name: '保留的输入' }
    await page.save(body)
    expect(post).toHaveBeenCalledWith('/codebooks', {
      name: '保留的输入',
      spaceId: 's',
      expectedDraftRev: 5,
    })
    expect(page.open.value).toBe(true)
    expect(page.draft.stale.value).toBe(true)
    expect(useWorkspace().stale).toBe(true)
    useWorkspace().refreshed({ ...space, draftRev: 6 })
    await page.save(body)
    expect(post).toHaveBeenCalledTimes(1)
    expect(body.name).toBe('保留的输入')
  })
  it('VIEWER无法打开或直接调用保存', async () => {
    useWorkspace().space = { ...space, role: 'VIEWER' }
    const post = vi.spyOn(useSession().api, 'post')
    const page = scope.run(() => useDraftCollection<{ id: string; name: string }>('/rules'))!
    page.edit(null)
    await page.save({ name: '不可写' })
    expect(page.open.value).toBe(false)
    expect(post).not.toHaveBeenCalled()
  })
})
