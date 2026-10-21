/** 系统管理 DTO：身份权限只来自当前服务端会话，不写入候选或浏览器持久存储。 */
export interface PermissionDefinition {
  code: string
  group: string
  name: string
  description: string
}
export interface RoleSummary {
  id: string
  code: string
  name: string
}
export interface SystemUser {
  id: string
  username: string
  displayName: string
  status: 'ACTIVE' | 'DISABLED'
  revision: number
  createdAt: string
  roles: RoleSummary[]
}
export interface SystemRole extends RoleSummary {
  description: string
  builtIn: boolean
  status: 'ACTIVE' | 'DISABLED'
  revision: number
  permissions: string[]
  memberCount: number
}
export interface IamAudit {
  id: number
  actorId: string
  actorName: string
  action: string
  targetId: string
  beforeState: unknown
  afterState: unknown
  createdAt: string
}
export interface IamAuditPage {
  items: IamAudit[]
  page: number
  pageSize: number
  total: number
}
