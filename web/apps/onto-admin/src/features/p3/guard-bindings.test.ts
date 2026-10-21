// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { setAuthorizedSession } from '../../test/authorizedSession'
import ElementPlus from 'element-plus'
import { ApiError, type Space } from '@onto/api-client'
import GuardsView from '../p2/GuardsView.vue'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'

const select = defineComponent({
  props: { modelValue: String, disabled: Boolean },
  emits: ['update:modelValue', 'change'],
  setup(props, { emit, slots }) {
    return () =>
      h(
        'select',
        {
          value: props.modelValue,
          disabled: props.disabled,
          onChange: (event: Event) => {
            const value = (event.target as HTMLSelectElement).value
            emit('update:modelValue', value)
            emit('change', value)
          },
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
const principal = { kind: 'PRINCIPAL_ATTRIBUTE', attribute: 'region' }
const first = { table: 'public.tasks', column: 'region', operator: 'IN' }
const second = { table: 'public.algorithms', column: 'region', operator: 'EQ' }
beforeEach(() => {
  setAuthorizedSession()
  useWorkspace().space = { ...space }
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})
async function page(guardCfg: Record<string, unknown>) {
  vi.spyOn(useSession().api, 'get').mockImplementation(
    async <T>(path: string): Promise<T> => (path === '/spaces/s' ? space : { guardCfg }) as T,
  )
  const wrapper = mount(GuardsView, { global })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
function button(wrapper: VueWrapper, label: string) {
  const result = wrapper.findAll('button').find((item) => item.text() === label)
  expect(result, label).toBeDefined()
  return result!
}
function mode(wrapper: VueWrapper) {
  const result = wrapper.find('[data-field=filter-mode] select')
  expect(result.exists(), '应提供单表/多表绑定模式').toBe(true)
  return result
}

describe('P3 JOIN 多表守卫与旧语义保留', () => {
  it('旧主体规则与单项 EQ 过滤同时存在时完整往返，不能静默丢弃任一项', async () => {
    const definition = {
      type: 'STRING',
      authorization_rule: principal,
      filter_binding: { ...first, operator: 'EQ' },
    }
    const wrapper = await page({ region: definition })
    const write = vi.spyOn(useSession().api, 'put').mockResolvedValue({ ...space, draftRev: 6 })
    expect(
      wrapper.get<HTMLInputElement>('[data-field=principal-attribute] input').element.value,
    ).toBe('region')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledWith('/spaces/s/guards', {
      expectedDraftRev: 5,
      guardCfg: { region: definition },
    })
  })
  it('已有多表绑定与主体规则完整往返并显示两个资源', async () => {
    const definition = {
      type: 'STRING',
      authorization_rule: principal,
      filter_bindings: [first, second],
    }
    const wrapper = await page({ region: definition })
    const write = vi.spyOn(useSession().api, 'put').mockResolvedValue({ ...space, draftRev: 6 })
    expect(wrapper.findAll('[data-binding-row]')).toHaveLength(2)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledWith('/spaces/s/guards', {
      expectedDraftRev: 5,
      guardCfg: { region: definition },
    })
  })
  it('OWNER 可新增多表过滤并逐表设置 IN/EQ，提交数组且不同时提交旧单项', async () => {
    const wrapper = await page({})
    const write = vi.spyOn(useSession().api, 'put').mockResolvedValue({ ...space, draftRev: 6 })
    await button(wrapper, '添加守卫').trigger('click')
    await wrapper.get('[data-field=guard-name] input').setValue('region')
    await wrapper.get('[data-field=binding-kind] select').setValue('FILTER')
    await mode(wrapper).setValue('MULTIPLE')
    await flushPromises()
    await button(wrapper, '添加源表绑定').trigger('click')
    const rows = wrapper.findAll('[data-binding-row]')
    expect(rows).toHaveLength(2)
    for (const [index, binding] of [first, second].entries()) {
      await rows[index]!.get('[data-field=filter-table] input').setValue(binding.table)
      await rows[index]!.get('[data-field=filter-column] input').setValue(binding.column)
      await rows[index]!.get('[data-field=filter-operator] select').setValue(binding.operator)
    }
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledWith('/spaces/s/guards', {
      expectedDraftRev: 5,
      guardCfg: { region: { type: 'STRING', filter_bindings: [first, second] } },
    })
  })
  it('旧单项显式切为多表时保留原资源和主体检查，可添加关联表', async () => {
    const wrapper = await page({
      region: { type: 'STRING', authorization_rule: principal, filter_binding: first },
    })
    const write = vi.spyOn(useSession().api, 'put').mockResolvedValue({ ...space, draftRev: 6 })
    await mode(wrapper).setValue('MULTIPLE')
    await flushPromises()
    await button(wrapper, '添加源表绑定').trigger('click')
    const row = wrapper.findAll('[data-binding-row]')[1]!
    await row.get('[data-field=filter-table] input').setValue(second.table)
    await row.get('[data-field=filter-column] input').setValue(second.column)
    await row.get('[data-field=filter-operator] select').setValue('EQ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledWith('/spaces/s/guards', {
      expectedDraftRev: 5,
      guardCfg: {
        region: { type: 'STRING', authorization_rule: principal, filter_bindings: [first, second] },
      },
    })
  })
  it('OWNER 可移除某表而保留数组形状和剩余绑定', async () => {
    const wrapper = await page({ region: { type: 'STRING', filter_bindings: [first, second] } })
    const write = vi.spyOn(useSession().api, 'put').mockResolvedValue({ ...space, draftRev: 6 })
    const row = wrapper.findAll('[data-binding-row]')[1]
    expect(row, '应显示可移除的终点绑定').toBeDefined()
    await row!
      .findAll('button')
      .find((item) => item.text() === '移除此源表绑定')!
      .trigger('click')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledWith('/spaces/s/guards', {
      expectedDraftRev: 5,
      guardCfg: { region: { type: 'STRING', filter_bindings: [first] } },
    })
  })
  it.each([
    ['重复表', [first, { ...second, table: first.table }], '重复'],
    ['资源含控制字符', [{ ...first, table: 'public.tasks\n' }], '控制字符'],
    ['SQL 列表达式', [{ ...first, column: 'region; DROP TABLE x' }], '单列'],
    ['空数组', [], '1–16'],
    [
      '超出数组上限',
      Array.from({ length: 17 }, (_, index) => ({ ...first, table: `table_${index}` })),
      '1–16',
    ],
  ])('拒绝%s并保留表单', async (_label, bindings, expected) => {
    const wrapper = await page({ region: { type: 'STRING', filter_bindings: bindings } })
    const write = vi.spyOn(useSession().api, 'put').mockResolvedValue(space)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(write).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain(expected)
  })
  it('多表守卫 409 保留所有输入并禁止借最新修订再次保存', async () => {
    const wrapper = await page({
      region: { type: 'STRING', authorization_rule: principal, filter_bindings: [first, second] },
    })
    const write = vi
      .spyOn(useSession().api, 'put')
      .mockRejectedValue(new ApiError('DRAFT_REV_CONFLICT', '守卫草稿变化', 409))
    const rows = wrapper.findAll('[data-binding-row]')
    expect(rows).toHaveLength(2)
    await rows[1]!.get('[data-field=filter-column] input').setValue('region_label')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(
      wrapper
        .findAll('[data-binding-row]')[1]!
        .get<HTMLInputElement>('[data-field=filter-column] input').element.value,
    ).toBe('region_label')
    useWorkspace().refreshed({ ...space, draftRev: 100 })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledTimes(1)
    expect(write).toHaveBeenCalledWith(
      '/spaces/s/guards',
      expect.objectContaining({ expectedDraftRev: 5 }),
    )
  })
  it('同时读到旧单项和数组时禁止静默选边，显式选形状后才允许保存', async () => {
    const wrapper = await page({
      region: {
        type: 'STRING',
        authorization_rule: principal,
        filter_binding: first,
        filter_bindings: [second],
      },
    })
    const write = vi.spyOn(useSession().api, 'put').mockResolvedValue({ ...space, draftRev: 6 })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(write).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('互斥')
    await mode(wrapper).setValue('SINGLE')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledWith('/spaces/s/guards', {
      expectedDraftRev: 5,
      guardCfg: {
        region: { type: 'STRING', authorization_rule: principal, filter_binding: first },
      },
    })
  })
  it('16 表边界可保存，新增入口在上限禁用', async () => {
    const bindings = Array.from({ length: 16 }, (_, index) => ({
      ...first,
      table: `public.table_${index}`,
    }))
    const wrapper = await page({ region: { type: 'STRING', filter_bindings: bindings } })
    const write = vi.spyOn(useSession().api, 'put').mockResolvedValue({ ...space, draftRev: 6 })
    expect(button(wrapper, '添加源表绑定').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledWith('/spaces/s/guards', {
      expectedDraftRev: 5,
      guardCfg: { region: { type: 'STRING', filter_bindings: bindings } },
    })
  })
})
