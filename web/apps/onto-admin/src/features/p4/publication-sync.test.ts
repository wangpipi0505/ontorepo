// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { setAuthorizedSession } from '../../test/authorizedSession'
import ElementPlus from 'element-plus'
import type { Publication, PublicationCandidate, Release, Space } from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import PublicationView from './PublicationView.vue'
import PublicationPanel from './PublicationPanel.vue'

const space = { id: 's', code: 'demo', role: 'OWNER', status: 'ACTIVE', draftRev: 5 } as Space
const candidates: PublicationCandidate[] = ['a', 'b'].map((id) => ({
  id,
  spaceId: 's',
  draftRev: 5,
  sha256: `sha-${id}`,
  bundleSha256: `sha-${id}`,
  status: 'VALIDATING',
  createdAt: '',
  formatVersion: 4,
  capability: 'PUBLICATION',
  preReleaseId: `release-${id}`,
  preVersionNo: id === 'a' ? 1 : 2,
}))
function detail(id = 'a', status: Publication['status'] = 'REVIEWING'): Publication {
  return {
    capability: 'CONTRACT',
    candidateId: id,
    candidateSha256: `sha-${id}`,
    draftRev: 5,
    status,
    preReleaseId: `release-${id}`,
    preVersionNo: id === 'a' ? 1 : 2,
    waivers: [],
    review: null,
    releaseId: status === 'COMMITTED' ? `release-${id}` : null,
    checks: (['CONFLICT', 'CONSISTENCY', 'CONTRACT', 'TEST', 'REVIEW'] as const).map((kind) => ({
      kind,
      conclusion: 'PASSED',
      reason: null,
      candidateSha256: `sha-${id}`,
      checkedAt: null,
      report: {},
    })),
    artifacts: (['BUNDLE', 'MANIFEST', 'CONTEXT_PACK', 'KNOWLEDGE_INDEX'] as const).map((kind) => ({
      kind,
      sha256: kind === 'BUNDLE' ? `sha-${id}` : `sha-${kind}`,
      size: 10,
      ready: true,
    })),
  }
}
const release: Release = {
  id: 'release-a',
  spaceId: 's',
  versionNo: 1,
  candidateId: 'a',
  status: 'READY',
  bundleSha256: 'sha-a',
  manifestSha256: 'sha-manifest',
  publishedAt: '',
  publishedBy: 'owner',
}
const wrappers: VueWrapper[] = []
function button(wrapper: VueWrapper, text: string) {
  const result = wrapper.findAll('button').find((item) => item.text() === text)
  expect(result, text).toBeDefined()
  return result!
}
function record(wrapper: VueWrapper, id: string) {
  const result = wrapper
    .findAll('button.selector')
    .find((item) => item.text().includes(`sha-${id}`))
  expect(result).toBeDefined()
  return result!
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((yes) => {
    resolve = yes
  })
  return { promise, resolve }
}
async function page() {
  const wrapper = mount(PublicationView, { global: { plugins: [ElementPlus] } })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
function response(path: string): unknown {
  if (path === '/spaces/s') return { ...space }
  if (path === '/spaces/s/candidates') return structuredClone(candidates)
  if (path.endsWith('/diff')) return { added: [], removed: [], changed: [] }
  if (path === '/candidates/a/publication') return detail()
  if (path === '/candidates/b/publication') return detail('b', 'TESTING')
  throw new Error(path)
}
beforeEach(() => {
  setAuthorizedSession()
  useWorkspace().space = { ...space }
  vi.spyOn(useSession().api, 'get').mockImplementation(
    async <T>(path: string): Promise<T> => response(path) as T,
  )
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})

describe('发布目录与详情事实同步', () => {
  it('初读和准备响应更新当前目录，保留同一面板的理由/复核与草稿修订', async () => {
    vi.spyOn(useSession().api, 'post').mockResolvedValue(detail('a', 'READY_TO_COMMIT'))
    const wrapper = await page()
    const panel = wrapper.getComponent(PublicationPanel).element
    expect(record(wrapper, 'a').text()).toContain('REVIEWING')
    expect(record(wrapper, 'b').text()).toContain('VALIDATING')
    await wrapper.get('[data-testid=publication-reason]').setValue('保留原人工复核说明')
    await wrapper.get('input[type=checkbox]').setValue(true)
    await button(wrapper, '准备发布制品').trigger('click')
    await flushPromises()
    expect(record(wrapper, 'a').text()).toContain('READY_TO_COMMIT')
    expect(wrapper.getComponent(PublicationPanel).element === panel).toBe(true)
    expect(wrapper.get<HTMLTextAreaElement>('[data-testid=publication-reason]').element.value).toBe(
      '保留原人工复核说明',
    )
    expect(wrapper.get<HTMLInputElement>('input[type=checkbox]').element.checked).toBe(true)
    expect(useWorkspace().space?.draftRev).toBe(5)
    expect(
      vi.mocked(useSession().api.get).mock.calls.filter(([path]) => path === '/spaces/s'),
    ).toHaveLength(1)
  })
  it('提交后以再次读取的真实详情COMMITTED同步目录，不重读草稿或清输入', async () => {
    let committed = false
    vi.mocked(useSession().api.get).mockImplementation(
      async <T>(path: string): Promise<T> =>
        (path === '/candidates/a/publication'
          ? detail('a', committed ? 'COMMITTED' : 'READY_TO_COMMIT')
          : response(path)) as T,
    )
    vi.spyOn(useSession().api, 'post').mockImplementation(async <T>() => {
      committed = true
      return release as T
    })
    const wrapper = await page()
    await wrapper.get('[data-testid=publication-reason]').setValue('提交后仍保留')
    await button(wrapper, '提交正式发布').trigger('click')
    await flushPromises()
    expect(record(wrapper, 'a').text()).toContain('COMMITTED')
    expect(wrapper.get<HTMLTextAreaElement>('[data-testid=publication-reason]').element.value).toBe(
      '提交后仍保留',
    )
    expect(
      vi.mocked(useSession().api.get).mock.calls.filter(([path]) => path === '/spaces/s'),
    ).toHaveLength(1)
  })
  it.each([{ candidateSha256: 'wrong-sha' }, { draftRev: 99 }])(
    '拒绝身份错配详情 %j，保留目录已确认状态',
    async (wrong) => {
      const wrapper = await page()
      vi.spyOn(useSession().api, 'post').mockResolvedValue({
        ...detail('a', 'READY_TO_COMMIT'),
        ...wrong,
      })
      await button(wrapper, '准备发布制品').trigger('click')
      await flushPromises()
      expect(record(wrapper, 'a').text()).toContain('REVIEWING')
      expect(record(wrapper, 'a').text()).not.toContain('READY_TO_COMMIT')
      expect(wrapper.text()).toContain('响应不属于当前候选摘要与修订')
    },
  )
  it('A的迟到读取不得更新已切到B的目录与详情', async () => {
    const pending = deferred<Publication>()
    vi.mocked(useSession().api.get).mockImplementation(
      async <T>(path: string): Promise<T> =>
        path === '/candidates/a/publication'
          ? ((await pending.promise) as T)
          : (response(path) as T),
    )
    const wrapper = await page()
    await record(wrapper, 'b').trigger('click')
    await flushPromises()
    pending.resolve(detail('a', 'COMMITTED'))
    await flushPromises()
    expect(record(wrapper, 'b').text()).toContain('TESTING')
    expect(record(wrapper, 'a').text()).toContain('VALIDATING')
    expect(wrapper.getComponent(PublicationPanel).text()).toContain('候选 b')
  })
  it('详情刷新晚于同空间重新进入时不得回传旧状态', async () => {
    const wrapper = await page()
    const pending = deferred<Publication>()
    vi.mocked(useSession().api.get).mockImplementation(
      async <T>(path: string): Promise<T> =>
        path === '/candidates/a/publication'
          ? ((await pending.promise) as T)
          : (response(path) as T),
    )
    await button(wrapper, '刷新发布状态').trigger('click')
    await useWorkspace().load('s')
    pending.resolve(detail('a', 'COMMITTED'))
    await flushPromises()
    expect(record(wrapper, 'a').text()).toContain('REVIEWING')
    expect(record(wrapper, 'a').text()).not.toContain('COMMITTED')
  })
  it('并行两次详情刷新仅最新响应可同步目录', async () => {
    const wrapper = await page()
    const first = deferred<Publication>(),
      second = deferred<Publication>()
    let calls = 0
    vi.mocked(useSession().api.get).mockImplementation(
      async <T>(path: string): Promise<T> =>
        path === '/candidates/a/publication'
          ? ((await (++calls === 1 ? first.promise : second.promise)) as T)
          : (response(path) as T),
    )
    await button(wrapper, '刷新发布状态').trigger('click')
    await button(wrapper, '刷新发布状态').trigger('click')
    second.resolve(detail('a', 'READY_TO_COMMIT'))
    await flushPromises()
    first.resolve(detail('a', 'TESTING'))
    await flushPromises()
    expect(record(wrapper, 'a').text()).toContain('READY_TO_COMMIT')
  })
})
