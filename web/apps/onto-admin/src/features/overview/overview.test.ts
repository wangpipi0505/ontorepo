// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { setAuthorizedSession } from '../../test/authorizedSession'
import ElementPlus from 'element-plus'
import type { Space } from '@onto/api-client'
import SpacesView from '../../views/SpacesView.vue'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import OverviewView from './OverviewView.vue'

const spaces = [
  {
    id: 'a',
    code: 'alpha',
    name: '设备空间',
    domain: '制造',
    status: 'ACTIVE',
    role: 'OWNER',
    draftRev: 4,
    classCount: 2,
    propertyCount: 3,
    operationCount: 1,
  },
  {
    id: 'b',
    code: 'beta',
    name: '归档空间',
    domain: '物流',
    status: 'ARCHIVED',
    role: 'VIEWER',
    draftRev: 1,
    classCount: 0,
    propertyCount: 0,
    operationCount: 0,
  },
] as Space[]
const wrappers: VueWrapper[] = []
function response(path: string): unknown {
  if (path === '/spaces') return spaces
  if (/^\/spaces\/[^/]+\/readiness$/.test(path))
    return {
      classCount: 2,
      propertyCount: 3,
      relationCount: 1,
      ruleCount: 2,
      assistantCount: 1,
      guardCount: 1,
      caseCount: 1,
      candidateCount: 1,
    }
  if (path.startsWith('/spaces/') && !path.endsWith('/conflicts'))
    return spaces.find((item) => path === `/spaces/${item.id}`)
  if (path.startsWith('/relations?')) return [{ id: 'rel' }]
  if (path.startsWith('/rules?')) return [{ id: 'rule' }, { id: 'rule2' }]
  if (path.endsWith('/conflicts'))
    return { errors: [{ kind: 'TYPE', message: '类型不匹配', entityIds: ['p'] }], warnings: [] }
  if (path.startsWith('/releases?')) return [{ id: 'release', versionNo: 3, status: 'DEPRECATED' }]
  if (path.startsWith('/mappings/validate?'))
    return {
      valid: false,
      checks: [
        {
          kind: 'REQUIRED_PROPERTY',
          passed: false,
          issues: [{ code: 'UNMAPPED', message: '必填属性缺少映射' }],
        },
      ],
      coverage: { mapped: 1, pending: 1, unmappable: 0 },
      coverageBasis: '当前类',
      affectedOperations: [],
    }
  throw new Error(path)
}
async function page() {
  const wrapper = mount(SpacesView, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
        SpaceDialog: true,
      },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
beforeEach(() => {
  setAuthorizedSession()
  vi.spyOn(useSession().api, 'get').mockImplementation(
    async <T>(path: string) => response(path) as T,
  )
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})

describe('空间查询与真实概览', () => {
  it('按名称、编码、领域和状态组合本地查询，空结果明确且输入不追加网络请求', async () => {
    const wrapper = await page()
    expect(wrapper.find('[aria-label="空间名称"]').exists()).toBe(true)
    const count = vi.mocked(useSession().api.get).mock.calls.length
    await wrapper.get('[aria-label="空间名称"]').setValue('设备')
    await wrapper.get('[aria-label="空间编码"]').setValue('alpha')
    await wrapper.get('[aria-label="所属领域"]').setValue('制造')
    await wrapper.get('[aria-label="空间状态"]').setValue('ACTIVE')
    await flushPromises()
    expect(wrapper.findAll('.space-card')).toHaveLength(1)
    await wrapper.get('[aria-label="空间编码"]').setValue('不存在')
    await flushPromises()
    expect(wrapper.text()).toContain('没有符合筛选条件的空间')
    expect(vi.mocked(useSession().api.get).mock.calls.length).toBe(count)
  })
  it('卡片展示关系、规则、正式版本状态与冲突，不把弃用当可消费', async () => {
    const wrapper = await page()
    expect(wrapper.text()).toContain('关系')
    expect(wrapper.text()).toContain('规则')
    expect(wrapper.text()).toContain('v3')
    expect(wrapper.text()).toContain('已弃用')
    expect(wrapper.text()).toContain('1 项错误')
    expect(wrapper.get('.space-title').attributes('href')).toBe('/spaces/a')
  })
  it('版本读取失败明确保留版本项及重试，其他指标仍可用', async () => {
    vi.mocked(useSession().api.get).mockImplementation(async <T>(path: string) => {
      if (path.startsWith('/releases?')) throw new Error('发布服务暂不可用')
      return response(path) as T
    })
    const wrapper = await page()
    expect(wrapper.text()).toContain('正式版本')
    expect(wrapper.text()).toContain('发布服务暂不可用')
    expect(wrapper.text()).not.toContain('尚未发布')
    expect(wrapper.text()).toContain('重试概览')
  })
  it('映射校验问题独立显示，不把模型无冲突当整体通过', async () => {
    const wrapper = await page()
    expect(wrapper.text()).toContain('映射校验')
    expect(wrapper.text()).toContain('1 项问题')
  })
  it('概览展示八类设计入口与可定位的真实问题', async () => {
    useWorkspace().space = spaces[0]!
    const wrapper = mount(OverviewView, {
      global: {
        plugins: [ElementPlus],
        stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } },
      },
    })
    wrappers.push(wrapper)
    await flushPromises()
    for (const path of [
      'model',
      'constraints',
      'rules',
      'strategies',
      'operations',
      'data-catalog',
      'releases',
      'publication',
    ])
      expect(wrapper.find(`a[href="/spaces/a/${path}"]`).exists()).toBe(true)
    expect(wrapper.text()).toContain('类型不匹配')
    expect(wrapper.text()).toContain('必填属性缺少映射')
  })
  it('每页只读六个空间、最多两张卡片并发，分页之外不预取', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    const many = Array.from({ length: 14 }, (_, index) => ({ ...spaces[0]!, id: `s${index}` }))
    vi.mocked(useSession().api.get).mockImplementation(async <T>(path: string): Promise<T> => {
      if (path === '/spaces') return many as T
      if (/^\/spaces\/s\d+$/.test(path)) {
        await gate
        return many.find((item) => `/spaces/${item.id}` === path) as T
      }
      return response(path) as T
    })
    const wrapper = await page()
    expect(
      vi.mocked(useSession().api.get).mock.calls.filter(([path]) => /^\/spaces\/s\d+$/.test(path)),
    ).toHaveLength(2)
    release()
    await flushPromises()
    expect(wrapper.findAll('.space-card')).toHaveLength(6)
    expect(
      vi
        .mocked(useSession().api.get)
        .mock.calls.some(([path]) => /^\/spaces\/s(6|7|8|9|1\d)$/.test(path)),
    ).toBe(false)
  })
  it('读取期间草稿变化时概览失败，不展示混合修订的统计', async () => {
    let calls = 0
    vi.mocked(useSession().api.get).mockImplementation(async <T>(path: string): Promise<T> => {
      if (path === '/spaces') return [spaces[0]] as T
      if (path === '/spaces/a') return { ...spaces[0], draftRev: ++calls } as T
      return response(path) as T
    })
    const wrapper = await page()
    expect(wrapper.text()).toContain('读取期间草稿发生变化')
    expect(wrapper.text()).not.toContain('v3')
  })
})
