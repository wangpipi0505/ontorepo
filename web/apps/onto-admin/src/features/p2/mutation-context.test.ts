// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { setAuthorizedSession } from '../../test/authorizedSession'
import { defineComponent, h } from 'vue'
import ElementPlus from 'element-plus'
import { ApiError, type Space } from '@onto/api-client'
import GuardsView from './GuardsView.vue'
import KnowledgeView from './KnowledgeView.vue'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'

const a = { id: 'A', role: 'OWNER', status: 'ACTIVE', draftRev: 5 } as Space
const b = { ...a, id: 'B', draftRev: 20 }
// 与已有表单测试一致，只隔离 jsdom 下拉浮层；表单、输入、按钮和业务组件保持真实。
const select = defineComponent({
  props: { modelValue: String, disabled: Boolean },
  emits: ['update:modelValue'],
  setup:
    (props, { emit, slots }) =>
    () =>
      h(
        'select',
        {
          value: props.modelValue,
          disabled: props.disabled,
          onChange: (event: Event) =>
            emit('update:modelValue', (event.target as HTMLSelectElement).value),
        },
        slots.default?.(),
      ),
})
const option = defineComponent({
  props: { value: String, label: String },
  setup: (props) => () => h('option', { value: props.value }, props.label),
})
const global = {
  plugins: [ElementPlus],
  stubs: { teleport: true, ElSelect: select, ElOption: option },
}
let wrappers: VueWrapper[] = []
function waitingWrite() {
  let resolve!: (value: Space) => void
  let reject!: (cause: unknown) => void
  const promise = new Promise<Space>((success, failure) => {
    resolve = success
    reject = failure
  })
  return { promise, resolve, reject }
}
beforeEach(() => {
  setAuthorizedSession()
  useWorkspace().space = { ...a }
  vi.spyOn(useSession().api, 'get').mockImplementation(async <T>(path: string): Promise<T> => {
    const result: unknown =
      path === '/spaces/A'
        ? {
            ...a,
            draftRev:
              useWorkspace().space?.id === 'A' ? useWorkspace().space!.draftRev : a.draftRev,
          }
        : path === '/spaces/B'
          ? b
          : path.endsWith('/guards')
            ? { guardCfg: { region: { type: 'STRING' } } }
            : []
    return result as T
  })
})
afterEach(() => {
  wrappers.forEach((wrapper) => wrapper.unmount())
  wrappers = []
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})
async function guards() {
  const wrapper = mount(GuardsView, { global, attachTo: document.body })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
describe('实际页面写上下文隔离', () => {
  it.each(['B', 'A'] as const)('A守卫保存后卸载，迟到409不会暂停新%s页面', async (destination) => {
    const response = waitingWrite()
    const write = vi.spyOn(useSession().api, 'put').mockReturnValue(response.promise)
    const old = await guards()
    await old.get('[data-field=guard-name] input').setValue('旧页面输入')
    await old.get('form').trigger('submit')
    expect(write).toHaveBeenCalledWith('/spaces/A/guards', {
      expectedDraftRev: 5,
      guardCfg: { 旧页面输入: { type: 'STRING' } },
    })
    old.unmount()
    await useWorkspace().load('B')
    if (destination === 'A') {
      await useWorkspace().load('A')
      useWorkspace().refreshed({ ...a, draftRev: 9 })
    }
    const current = await guards()
    response.reject(new ApiError('DRAFT_REV_CONFLICT', '旧A守卫冲突', 409))
    await flushPromises()
    expect(useWorkspace().space?.id).toBe(destination)
    expect(useWorkspace().canWrite).toBe(true)
    expect(current.get('button[type=submit]').attributes('disabled')).toBeUndefined()
    expect(current.text()).not.toContain('旧A守卫冲突')
  })
  it('A→B→A后旧成功响应不会回写新页面修订或刷新新页面', async () => {
    const response = waitingWrite()
    vi.spyOn(useSession().api, 'put').mockReturnValue(response.promise)
    const old = await guards()
    await old.get('form').trigger('submit')
    old.unmount()
    await useWorkspace().load('B')
    await useWorkspace().load('A')
    useWorkspace().refreshed({ ...a, draftRev: 9 })
    const current = await guards()
    await current.get('[data-field=guard-name] input').setValue('新页面输入')
    const beforeReads = vi.mocked(useSession().api.get).mock.calls.length
    response.resolve({ ...a, draftRev: 6 })
    await flushPromises()
    expect(useWorkspace().space?.draftRev).toBe(9)
    expect(vi.mocked(useSession().api.get).mock.calls).toHaveLength(beforeReads)
    expect(current.get<HTMLInputElement>('[data-field=guard-name] input').element.value).toBe(
      '新页面输入',
    )
  })
  it('当前守卫真实409仍暂停写入，保留输入并锁住旧编辑修订', async () => {
    const response = waitingWrite()
    const write = vi.spyOn(useSession().api, 'put').mockReturnValue(response.promise)
    const wrapper = await guards()
    await wrapper.get('[data-field=guard-name] input').setValue('必须保留')
    await wrapper.get('form').trigger('submit')
    response.reject(new ApiError('DRAFT_REV_CONFLICT', '当前空间真实冲突', 409))
    await flushPromises()
    expect(useWorkspace().stale).toBe(true)
    expect(wrapper.text()).toContain('当前空间真实冲突')
    expect(wrapper.get<HTMLInputElement>('[data-field=guard-name] input').element.value).toBe(
      '必须保留',
    )
    useWorkspace().refreshed({ ...a, draftRev: 9 })
    await wrapper.get('form').trigger('submit')
    expect(write).toHaveBeenCalledTimes(1)
    expect(wrapper.get('button[type=submit]').attributes('disabled')).toBeDefined()
  })
  it.each(['CONFLICT', 'SUCCESS'] as const)(
    '知识页直接mutation.run的迟到%s也不能污染新上下文',
    async (outcome) => {
      const response = waitingWrite()
      const write = vi.spyOn(useSession().api, 'post').mockReturnValue(response.promise)
      const old = mount(KnowledgeView, { global, attachTo: document.body })
      wrappers.push(old)
      await flushPromises()
      await old
        .findAll('button')
        .find((button) => button.text() === '新建知识')!
        .trigger('click')
      await flushPromises()
      const textInputs = old.get('.el-dialog').findAll('input[type=text]')
      await textInputs[0]!.setValue('旧空间术语')
      await textInputs[1]!.setValue('原始来源')
      const textareas = old.get('.el-dialog').findAll('textarea')
      await textareas[1]!.setValue('原始定义')
      await old.get('.el-dialog form').trigger('submit')
      expect(write).toHaveBeenCalledWith(
        '/knowledge',
        expect.objectContaining({ spaceId: 'A', expectedDraftRev: 5 }),
      )
      old.unmount()
      await useWorkspace().load('B')
      if (outcome === 'SUCCESS') {
        await useWorkspace().load('A')
        useWorkspace().refreshed({ ...a, draftRev: 9 })
        response.resolve({ ...a, draftRev: 6 })
      } else response.reject(new ApiError('DRAFT_REV_CONFLICT', '旧知识冲突', 409))
      await flushPromises()
      expect(useWorkspace().space?.id).toBe(outcome === 'SUCCESS' ? 'A' : 'B')
      expect(useWorkspace().space?.draftRev).toBe(outcome === 'SUCCESS' ? 9 : 20)
      expect(useWorkspace().canWrite).toBe(true)
    },
  )
})
