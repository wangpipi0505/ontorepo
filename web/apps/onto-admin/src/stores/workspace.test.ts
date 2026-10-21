import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setAuthorizedSession } from '../test/authorizedSession'
import { effectScope } from 'vue'
import { ApiError, type Space } from '@onto/api-client'
import { useWorkspace } from './workspace'
import { useMutation } from '../composables/useMutation'
import { useSession } from './session'

const original: Space = {
  id: 'space',
  code: 'test',
  name: '设备领域',
  namespaceUri: 'https://example.org/test/',
  domain: '',
  status: 'ACTIVE',
  draftRev: 5,
  role: 'EDITOR',
  classCount: 1,
  propertyCount: 0,
  operationCount: 0,
  createdAt: '2026-10-20T00:00:00Z',
}

describe('跨对象编辑的冲突边界', () => {
  beforeEach(() => setAuthorizedSession())
  it('属性409不会给旧对象名称授予新修订写权限', async () => {
    const workspace = useWorkspace()
    workspace.space = { ...original }
    const oldClass = { name: '旧名称' }
    const mutation = useMutation()
    await mutation.run(async () => {
      throw new ApiError('DRAFT_REV_CONFLICT', '另一人已把对象改名到r6', 409)
    })
    expect(workspace.space.draftRev).toBe(5)
    expect(workspace.canWrite).toBe(false)
    const updateClass = vi.fn()
    if (workspace.canWrite) updateClass(oldClass, workspace.space.draftRev)
    expect(updateClass).not.toHaveBeenCalled()
    // 页面先替换完整模型实体，再提交对应的新空间快照，才恢复后续编辑。
    const latestClass = { name: '其他编辑者的新名称' }
    workspace.refreshed({ ...original, draftRev: 6 })
    expect(workspace.canWrite).toBe(true)
    expect(latestClass.name).toBe('其他编辑者的新名称')
    expect(workspace.space.draftRev).toBe(6)
  })
  it.each(['B', 'A'] as const)('空间A迟到409不会污染切换后的%s上下文', async (destination) => {
    const workspace = useWorkspace()
    const a = { ...original, id: 'A' }
    workspace.space = a
    let reject!: (cause: unknown) => void
    const waiting = new Promise<void>((_, fail) => {
      reject = fail
    })
    const mutation = useMutation()
    const pending = mutation.run(() => waiting)
    // 同步变化也必须区分 A→B→A，不能只在 catch 比较空间 ID。
    workspace.space = { ...original, id: 'B' }
    if (destination === 'A') workspace.space = { ...a, draftRev: 9 }
    reject(new ApiError('DRAFT_REV_CONFLICT', '旧A请求冲突', 409))
    expect(await pending).toBe(false)
    expect(workspace.space?.id).toBe(destination)
    expect(workspace.stale).toBe(false)
    expect(workspace.canWrite).toBe(true)
    expect(mutation.error.value).toBe('')
  })
  it('同空间重新加载已建立新上下文，旧请求冲突不能暂停该上下文', async () => {
    const workspace = useWorkspace()
    workspace.space = { ...original }
    let reject!: (cause: unknown) => void
    const waiting = new Promise<void>((_, fail) => {
      reject = fail
    })
    const mutation = useMutation()
    const pending = mutation.run(() => waiting)
    vi.spyOn(useSession().api, 'get').mockResolvedValue({ ...original, draftRev: 9 })
    await workspace.load(original.id)
    reject(new ApiError('DRAFT_REV_CONFLICT', '旧页面冲突', 409))
    expect(await pending).toBe(false)
    expect(workspace.space?.draftRev).toBe(9)
    expect(workspace.canWrite).toBe(true)
  })
  it('组件作用域结束后同空间的迟到409也不能暂停其他页面', async () => {
    const workspace = useWorkspace()
    workspace.space = { ...original }
    const scope = effectScope()
    const mutation = scope.run(() => useMutation())!
    let reject!: (cause: unknown) => void
    const waiting = new Promise<void>((_, fail) => {
      reject = fail
    })
    const pending = mutation.run(() => waiting)
    scope.stop()
    reject(new ApiError('DRAFT_REV_CONFLICT', '已卸载页面冲突', 409))
    expect(await pending).toBe(false)
    expect(workspace.canWrite).toBe(true)
    expect(mutation.error.value).toBe('')
  })
})
