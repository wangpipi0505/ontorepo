// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, type Component } from 'vue'
import { setAuthorizedSession } from '../../test/authorizedSession'
import ElementPlus from 'element-plus'
import { ApiError, type Candidate, type Space } from '@onto/api-client'
import InternalCallPanel from '../p2/InternalCallPanel.vue'
import CandidatesView from '../../views/CandidatesView.vue'
import TestCasesView from '../p1/TestCasesView.vue'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'

const select = defineComponent({
  props: { modelValue: String, disabled: Boolean },
  emits: ['update:modelValue'],
  setup(props, { emit, slots }) {
    return () =>
      h(
        'select',
        {
          value: props.modelValue,
          disabled: props.disabled,
          onChange: (event: Event) =>
            emit('update:modelValue', (event.target as HTMLSelectElement).value),
        },
        slots.default?.(),
      )
  },
})
const option = defineComponent({
  props: { value: String, label: String },
  setup: (props) => () => h('option', { value: props.value }, props.label),
})
const global = {
  plugins: [ElementPlus],
  stubs: { teleport: true, ElSelect: select, ElOption: option },
}
const wrappers: VueWrapper[] = []
const space = { id: 's', role: 'OWNER', status: 'ACTIVE', draftRev: 5 } as Space
const candidate = {
  id: 'c',
  draftRev: 5,
  sha256: 'candidate-sha',
  createdAt: '2026-10-20T00:00:00Z',
  status: 'TESTING',
} as Candidate
const operation = { id: 'o', code: 'query', name: '冻结操作' }
beforeEach(() => {
  setAuthorizedSession()
  useWorkspace().space = { ...space }
  vi.spyOn(useSession().api, 'get').mockImplementation(async <T>(path: string): Promise<T> => {
    const value: unknown =
      path === '/spaces/s'
        ? space
        : path === '/spaces/s/candidates'
          ? [candidate]
          : path.startsWith('/candidates/c/operations') || path.startsWith('/operations?')
            ? [operation]
            : []
    return value as T
  })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})
async function render(component: Component, props: Record<string, unknown> = {}) {
  const wrapper = mount(component, { props, global, attachTo: document.body })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
function field(wrapper: VueWrapper, prefix: string) {
  const result = wrapper
    .findAll('.el-form-item')
    .find((item) => item.find('.el-form-item__label').text().startsWith(prefix))
  expect(result, prefix).toBeDefined()
  return result!
}
const examples: [string, unknown][] = [
  ['array', [1, false, null]],
  ['false', false],
  ['null', null],
  ['zero', 0],
  ['string', '业务参数'],
  ['true', true],
]

describe('P2-R2 操作输入保留任意 JSON 根', () => {
  it.each(examples)('内部调用原样发送 %s，身份仍由服务器取得', async (_label, value) => {
    const wrapper = await render(InternalCallPanel, { candidate })
    const request = vi
      .spyOn(useSession().api, 'requestEnvelope')
      .mockRejectedValue(new ApiError('GUARD_BINDING_NOT_APPLICABLE', '仍需真实守卫', 403))
    await field(wrapper, '冻结操作').get('select').setValue('query')
    await field(wrapper, '操作输入（JSON').get('textarea').setValue(JSON.stringify(value))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(request).toHaveBeenCalledWith('/internal/candidates/c/ops/query', 'POST', value)
    expect(field(wrapper, '操作输入（JSON').get('.el-form-item__label').text()).toBe(
      '操作输入（JSON）',
    )
  })
  it.each(examples)('候选试执行原样发送 %s', async (_label, value) => {
    const wrapper = await render(CandidatesView)
    const request = vi
      .spyOn(useSession().api, 'post')
      .mockRejectedValue(new ApiError('GUARD_BINDING_NOT_APPLICABLE', '仍需真实守卫', 403))
    await field(wrapper, '选择冻结操作').get('select').setValue('o')
    await field(wrapper, '试执行输入（JSON').get('textarea').setValue(JSON.stringify(value))
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '试执行')!
      .trigger('click')
    await flushPromises()
    expect(request).toHaveBeenCalledWith('/candidates/c/trial', { operationId: 'o', input: value })
    expect(field(wrapper, '试执行输入（JSON').get('.el-form-item__label').text()).toBe(
      '试执行输入（JSON）',
    )
  })
  it.each(examples)('已存用例 %s 不被空对象替换，编辑后仍原样保存', async (_label, value) => {
    const testCase = {
      id: 'tc',
      spaceId: 's',
      name: '保真用例',
      question: '输入保真',
      operationId: 'o',
      input: value,
      expected: {},
      matchMode: 'EXACT',
      enabled: true,
    }
    const get = vi.mocked(useSession().api.get),
      original = get.getMockImplementation()!
    get.mockImplementation(
      async <T>(path: string): Promise<T> =>
        (path === '/test-cases?spaceId=s' ? [testCase] : await original(path)) as T,
    )
    const wrapper = await render(TestCasesView)
    const request = vi
      .spyOn(useSession().api, 'put')
      .mockRejectedValue(new ApiError('DRAFT_REV_CONFLICT', '保留用例输入', 409))
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '编辑')!
      .trigger('click')
    await flushPromises()
    const input = field(wrapper, '用例输入（JSON').get<HTMLTextAreaElement>('textarea')
    expect(JSON.parse(input.element.value)).toEqual(value)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(request).toHaveBeenCalledWith(
      '/test-cases/tc',
      expect.objectContaining({ expectedDraftRev: 5, input: value, expected: {} }),
    )
    expect(field(wrapper, '用例输入（JSON').get('.el-form-item__label').text()).toBe(
      '用例输入（JSON）',
    )
  })
  it('非法业务 JSON 提示不包含原始输入片段', async () => {
    const wrapper = await render(InternalCallPanel, { candidate })
    const request = vi.spyOn(useSession().api, 'requestEnvelope')
    await field(wrapper, '冻结操作').get('select').setValue('query')
    await field(wrapper, '操作输入（JSON').get('textarea').setValue('SENTINEL_BUSINESS_INPUT')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(request).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('SENTINEL')
    expect(wrapper.text()).toContain('有效的 JSON')
  })
  it('放开用例输入不会放开预期结果的对象约束', async () => {
    const wrapper = await render(TestCasesView)
    const request = vi.spyOn(useSession().api, 'post')
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '新建用例')!
      .trigger('click')
    await flushPromises()
    await field(wrapper, '名称').get('input').setValue('结果仍需对象')
    await field(wrapper, '问题').get('textarea').setValue('校验对象约束')
    await field(wrapper, '预期命中操作').get('select').setValue('o')
    await field(wrapper, '用例输入（JSON').get('textarea').setValue('false')
    await field(wrapper, '预期结果').get('textarea').setValue('[]')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(request).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('预期结果须为有效的 JSON 对象')
  })
})
