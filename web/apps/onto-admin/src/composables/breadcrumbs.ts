/** 空间内面包屑：按容器路由与 tab query 生成“区 / 页”层级，页面标题仍由各页负责。 */
export interface Crumb {
  label: string
  to?: string
}
const areas: Record<string, string> = {
  model: '概念建模',
  operations: '操作与测试',
  testing: '操作与测试',
  executions: '操作与测试',
  knowledge: '知识与数据',
  datasources: '知识与数据',
  mappings: '知识与数据',
  suggestions: '知识与数据',
  'data-catalog': '知识与数据',
  imports: '知识与数据',
  releases: '发布与服务',
  audit: '溯源审计',
  settings: '空间设置',
  data: '知识与数据',
  delivery: '测试与服务封装',
  trace: '数据溯源审计',
}
const pages: Record<string, string> = {
  model: '建模工作台',
  operations: '模型操作',
  testing: '验证与候选',
  executions: '执行记录',
  knowledge: '知识管理',
  datasources: '数据源与凭据',
  mappings: '字段映射工作台',
  suggestions: '三来源建模抽取',
  'data-catalog': '数据目录',
  imports: 'TTL / OWL 导入导出',
  releases: '发布与版本',
  audit: '审计记录',
  data: '知识与数据',
  delivery: '测试与服务封装',
  trace: '数据溯源审计',
  settings: '空间设置',
}
const tabs: Record<string, Record<string, string>> = {
  model: {
    classes: '对象与属性',
    codebooks: '码表',
    relations: '关系',
    constraints: '约束',
    rules: '规则',
    conflicts: '冲突检查',
  },
  operations: { definitions: '操作定义', assistant: '助手声明', strategies: '执行策略' },
  testing: { cases: '用例与试执行', candidates: '候选与试执行', supply: '候选供给' },
  releases: {
    publication: '发布向导',
    versions: '正式版本',
    events: '事件与收件箱',
    consumption: '服务消费',
  },
  settings: { guards: '守卫属性', members: '成员与授权' },
}
export function breadcrumbs(path: string, queryTab?: string): Crumb[] {
  const segments = path.split('/').filter(Boolean)
  if (['data', 'delivery', 'trace'].includes(segments[0] ?? ''))
    return [{ label: pages[segments[0]!] ?? '功能模块' }]
  const index = segments.indexOf('spaces')
  const rest = index >= 0 ? segments.slice(index + 2) : []
  if (!rest.length) return [{ label: '空间概览' }]
  const page = rest[0] ?? ''
  if (page === 'executions' && rest.length > 1) {
    const base = `/spaces/${segments[index + 1]}/executions`
    return [
      { label: '操作与测试' },
      { label: '执行记录', to: base },
      { label: '执行追溯' },
    ]
  }
  const area = areas[page]
  if (!area) return [{ label: pages[page] ?? '当前页面' }]
  const current = tabs[page]?.[queryTab ?? ''] ?? pages[page] ?? area
  return [{ label: area }, { label: current }]
}
