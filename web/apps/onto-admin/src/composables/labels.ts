import type { Space } from '@onto/api-client'
/** 将接口枚举转换为用户可理解的空间权限名称。 */
export const roleLabels: Record<Space['role'], string> = {
  OWNER: '所有者',
  EDITOR: '编辑者',
  VIEWER: '查看者',
}
