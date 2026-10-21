// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, type Component } from 'vue'
import { setAuthorizedSession } from '../../test/authorizedSession'
import ElementPlus from 'element-plus'
import type { Space } from '@onto/api-client'
import AssistantForm from '../p2/AssistantForm.vue'
import GuardsView from '../p2/GuardsView.vue'
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
const wrappers: VueWrapper[] = [],
  space = { id: 's', role: 'OWNER', status: 'ACTIVE', draftRev: 5 } as Space
beforeEach(() => {
  setAuthorizedSession()
  useWorkspace().space = { ...space }
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})
async function render(component: Component, props: Record<string, unknown> = {}) {
  const wrapper = mount(component, { props, global })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
function field(wrapper: VueWrapper, label: string) {
  const result = wrapper
    .findAll('.el-form-item')
    .find((item) => item.find('.el-form-item__label').text() === label)
  expect(result, label).toBeDefined()
  return result!
}
const values: [string, string, unknown][] = [
  ['false', 'boolean', false],
  ['true', 'boolean', true],
  ['null', 'null', null],
  ['zero', 'number', 0],
  ['string', 'string', 'hello'],
  ['array', 'array', [1, false, null]],
]
function declaration(type: string, examples: unknown[]) {
  return {
    operationId: 'o',
    inputSchema: { type },
    outputSchema: { type: 'object' },
    assistantTool: {
      name: '根值操作',
      param_examples: examples,
      error_semantics: [],
      return_spec: {},
    },
  }
}
async function assistant(type: string, examples: unknown[]) {
  return render(AssistantForm, {
    value: declaration(type, examples),
    canWrite: true,
    pending: false,
    stale: false,
    error: '',
  })
}

describe('R5 参数示例与错误语义分开校验', () => {
  it.each(values)('读取已有 %s 根声明后原样保存', async (_label, type, value) => {
    const wrapper = await assistant(type, [value])
    expect(
      JSON.parse(
        field(wrapper, '参数示例（JSON 数组）').get<HTMLTextAreaElement>('textarea').element.value,
      ),
    ).toEqual([value])
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')?.[0]?.[0]).toMatchObject({
      inputSchema: { type },
      assistantTool: { param_examples: [value] },
    })
  })
  it.each(values)('可通过表单维护 %s 根示例，外层始终为数组', async (_label, type, value) => {
    const wrapper = await assistant(type, [])
    await field(wrapper, '参数示例（JSON 数组）')
      .get('textarea')
      .setValue(JSON.stringify([value]))
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')?.[0]?.[0]).toMatchObject({
      assistantTool: { param_examples: [value] },
    })
  })
  it.each(['false', 'null', '{}', 'SENTINEL_EXAMPLE'])(
    '参数示例外层不是数组或语法错误时拒绝且保留原文：%s',
    async (text) => {
      const wrapper = await assistant('boolean', [false])
      const input = field(wrapper, '参数示例（JSON 数组）').get<HTMLTextAreaElement>('textarea')
      await input.setValue(text)
      await wrapper.get('form').trigger('submit')
      expect(wrapper.emitted('save')).toBeUndefined()
      expect(input.element.value).toBe(text)
      await vi.waitFor(() =>
        expect(field(wrapper, '参数示例（JSON 数组）').text()).toContain('JSON 数组'),
      )
      expect(wrapper.text()).not.toContain('SENTINEL')
    },
  )
  it.each(['[false]', '[null]', '[[1]]'])(
    '错误语义仍要求对象数组，不能跟随示例放宽：%s',
    async (text) => {
      const wrapper = await assistant('boolean', [false])
      await field(wrapper, '错误语义映射（JSON 数组）').get('textarea').setValue(text)
      await wrapper.get('form').trigger('submit')
      expect(wrapper.emitted('save')).toBeUndefined()
      await vi.waitFor(() =>
        expect(field(wrapper, '错误语义映射（JSON 数组）').text()).toContain('每项为对象'),
      )
    },
  )
})

const resources = ['7fa12d3a-4e5b-4b21-a5aa-2357aa2e9090.csv', '/items', 'prefix/object.parquet']
const authorization_rule = { kind: 'PRINCIPAL_ATTRIBUTE', attribute: 'region' }
async function guards(table: string, multiple: boolean) {
  const binding = { table, column: 'region', operator: 'IN' }
  const definition = {
    type: 'STRING',
    authorization_rule,
    ...(multiple ? { filter_bindings: [binding] } : { filter_binding: binding }),
  }
  vi.spyOn(useSession().api, 'get').mockImplementation(
    async <T>(path: string): Promise<T> =>
      (path === '/spaces/s' ? space : { guardCfg: { region: definition } }) as T,
  )
  const write = vi.spyOn(useSession().api, 'put').mockResolvedValue({ ...space, draftRev: 6 })
  return { wrapper: await render(GuardsView), write, definition }
}
describe('R6 守卫 table 保存协议原始资源', () => {
  it.each(resources)('旧单项允许原始资源 %s 并保留主体规则', async (resource) => {
    const { wrapper, write, definition } = await guards(resource, false)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledWith('/spaces/s/guards', {
      expectedDraftRev: 5,
      guardCfg: { region: definition },
    })
  })
  it.each(resources)('数组绑定允许原始资源 %s 并保留主体规则', async (resource) => {
    const { wrapper, write, definition } = await guards(resource, true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledWith('/spaces/s/guards', {
      expectedDraftRev: 5,
      guardCfg: { region: definition },
    })
  })
  it('不规范化或删改原始资源空格，仍由冻结元数据精确核验', async () => {
    const { wrapper, write, definition } = await guards(' prefix/数据 文件.parquet ', true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledWith('/spaces/s/guards', {
      expectedDraftRev: 5,
      guardCfg: { region: definition },
    })
  })
  it('512 字符的资源边界可保存', async () => {
    const { wrapper, write, definition } = await guards('x'.repeat(512), false)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledWith('/spaces/s/guards', {
      expectedDraftRev: 5,
      guardCfg: { region: definition },
    })
  })
  it.each(['', ' ', 'x'.repeat(513), 'tasks\n', 'tasks\u0000', 'tasks\u0085'])(
    '通用结构拒绝空值、超长或控制字符资源',
    async (resource) => {
      const { wrapper, write } = await guards(resource, true)
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(write).not.toHaveBeenCalled()
      expect(wrapper.text()).toContain('资源必须非空、长度不超过 512 且不含控制字符')
    },
  )
})
