/** 审计与执行枚举的统一中文映射，页面与全局视图共用。 */
export const auditSubjectLabels: Record<string, string> = {
  CANDIDATE: '候选',
  RELEASE: '版本',
  DATASOURCE: '数据源',
  MAPPING: '映射',
  OPERATION: '操作',
  SPACE: '空间',
}
export const auditResultLabels: Record<string, string> = {
  SUCCESS: '成功',
  FAILURE: '失败',
  DENIED: '拒绝',
}
export const executionStatusLabels: Record<string, string> = {
  SUCCEEDED: '成功',
  FAILED: '失败',
}
export const executionCallerLabels: Record<string, string> = {
  SERVICE: '服务调用',
  EXTERNAL: '外部调用',
  LOCAL_TEST: '本地测试',
  TRIAL: '候选试执行',
}
export const auditActionLabels: Record<string, string> = {
  SPACE_CREATE: '创建空间',
  SPACE_ARCHIVE: '归档空间',
  CANDIDATE_FREEZE: '冻结候选',
  CANDIDATE_VALIDATE: '候选机器检查',
  CANDIDATE_REVIEW: '人工复核',
  CANDIDATE_CANCEL: '取消候选',
  CANDIDATE_TRIAL: '候选试执行',
  RELEASE_COMMIT: '发布提交',
  RELEASE_EXECUTE: '正式服务调用',
  RELEASE_DEPRECATE: '弃用版本',
  RELEASE_CORRUPTED: '制品损坏隔离',
  DATASOURCE_CREATE: '新增数据源',
  DATASOURCE_UPDATE: '修改数据源',
  DATASOURCE_DELETE: '删除数据源',
  DATASOURCE_PROBE: '连接测试',
  DATASOURCE_SYNC: '元数据同步',
  MAPPING_OBJECT_SAVE: '对象映射保存',
  MAPPING_PROPERTY_SAVE: '属性映射保存',
  MAPPING_RELATION_SAVE: '关系映射保存',
  MAPPING_DELETE: '删除映射',
  DATA_QUERY_SAVE: '实例查询配置',
  ENDPOINT_STATUS: '端点启停',
  ENDPOINT_GRANT: '端点授权',
  API_CREDENTIAL_CREATE: '创建 API key',
  API_CREDENTIAL_REVOKE: '撤销 API key',
  TEST_CASE_SAVE: '用例保存',
  CANDIDATE_CHECK: '候选检查',
}
export function auditActionLabel(action: string) {
  return auditActionLabels[action] || action
}
