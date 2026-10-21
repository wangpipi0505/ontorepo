import { effectScope } from 'vue'
import { describe, expect, it } from 'vitest'
import { useResource } from './useResource'

describe('资源异步版本隔离', () => {
  it('先发请求晚到时不能覆盖当前对象的数据', async () => {
    const scope = effectScope()
    const resource = scope.run(() => useResource<string[]>([]))!
    let finishOld!: (value: string[]) => void
    const old = resource.load(
      () =>
        new Promise<string[]>((resolve) => {
          finishOld = resolve
        }),
    )
    await resource.load(async () => ['当前对象属性'])
    finishOld(['旧对象属性'])
    await old
    expect(resource.data.value).toEqual(['当前对象属性'])
    scope.stop()
  })
  it('离开页面后迟到的失败不再改变页面状态', async () => {
    const scope = effectScope()
    const resource = scope.run(() => useResource<string[]>([]))!
    let failOld!: (reason: Error) => void
    const old = resource.load(
      () =>
        new Promise<string[]>((_resolve, reject) => {
          failOld = reject
        }),
    )
    scope.stop()
    failOld(new Error('旧请求失败'))
    await old
    expect(resource.error.value).toBe('')
    expect(resource.loading.value).toBe(false)
  })
})
