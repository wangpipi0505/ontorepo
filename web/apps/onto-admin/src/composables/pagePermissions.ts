/** 路由导航与写按钮共用功能策略；具体操作另叠加 execute/create 权限，后端仍独立鉴权。 */
export const spacePagePermissions: Record<string, { read: string; write: string }> = {
  model: { read: 'model.read', write: 'model.write' },
  codebooks: { read: 'model.read', write: 'model.write' },
  relations: { read: 'model.read', write: 'model.write' },
  constraints: { read: 'rule.read', write: 'rule.write' },
  rules: { read: 'rule.read', write: 'rule.write' },
  conflicts: { read: 'rule.read', write: 'rule.write' },
  strategies: { read: 'rule.read', write: 'rule.write' },
  operations: { read: 'operation.read', write: 'operation.write' },
  assistant: { read: 'operation.read', write: 'operation.write' },
  knowledge: { read: 'knowledge.read', write: 'knowledge.write' },
  guards: { read: 'grant.read', write: 'grant.write' },
  grants: { read: 'grant.read', write: 'grant.write' },
  datasources: { read: 'datasource.read', write: 'datasource.write' },
  mappings: { read: 'mapping.read', write: 'mapping.write' },
  'data-query': { read: 'mapping.read', write: 'mapping.write' },
  suggestions: { read: 'ingest.read', write: 'ingest.write' },
  'data-catalog': { read: 'mapping.read', write: '' },
  imports: { read: 'model.read', write: 'model.write' },
  'test-cases': { read: 'test.read', write: 'test.write' },
  testing: { read: 'test.read', write: 'test.write' },
  candidates: { read: 'candidate.read', write: 'candidate.write' },
  supply: { read: 'candidate.read', write: 'service.execute' },
  executions: { read: 'trace.read', write: '' },
  publication: { read: 'release.read', write: 'release.write' },
  releases: { read: 'release.read', write: 'release.write' },
  consumption: { read: 'release.read', write: 'grant.write' },
  events: { read: 'trace.read', write: 'release.write' },
  audit: { read: 'trace.read', write: '' },
}
export function pagePermission(path: string) {
  if (path.startsWith('/system/users'))
    return { read: 'system.user.read', write: 'system.user.write' }
  if (path.startsWith('/system/roles'))
    return { read: 'system.role.read', write: 'system.role.write' }
  if (path.startsWith('/system/audit')) return { read: 'system.audit.read', write: '' }
  if (path === '/spaces' || /^\/spaces\/[^/]+$/.test(path))
    return { read: 'space.read', write: 'space.write' }
  const tab = path.split('/')[3]
  return spacePagePermissions[tab] || null
}
