/** 页面到技术要求编号的只读角标映射；仅用于验收定位，不改变权限或业务行为。 */
const tags: Record<string, string[]> = {
  model: ['2.1①', '2.1②', '2.1③', '2.1④'],
  codebooks: ['2.1③'],
  relations: ['2.1④'],
  constraints: ['2.1①'],
  rules: ['2.1⑤', '2.1⑦'],
  conflicts: ['2.1⑧'],
  strategies: ['2.1⑧'],
  operations: ['2.1⑤', '2.4②'],
  assistant: ['2.4②'],
  guards: ['2.5②'],
  grants: ['2.4③'],
  knowledge: ['2.3①'],
  datasources: ['2.3②', '2.3③'],
  mappings: ['2.3④', '2.3⑥'],
  'data-query': ['2.4②'],
  suggestions: ['2.1①'],
  'data-catalog': ['2.2④'],
  imports: ['2.1①'],
  'test-cases': ['2.4①'],
  candidates: ['2.2③'],
  supply: ['2.2③'],
  executions: ['2.5①', '2.5③'],
  publication: ['2.2③', '2.4②', '2.4③'],
  releases: ['2.2③', '2.4③'],
  consumption: ['2.4③'],
  events: ['2.4③'],
  audit: ['2.5②', '2.5③'],
}
export function requirementTags(path: string): string[] {
  const segments = path.split('/').filter(Boolean)
  const page = segments.at(-1) ?? ''
  return tags[page] ?? []
}
