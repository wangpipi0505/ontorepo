import type { SourceKind } from '@onto/api-client'

export const sourceLabels: Record<SourceKind, string> = {
  RELATIONAL: '关系数据库',
  WAREHOUSE: '数据仓库',
  LAKE: '数据湖 / Parquet',
  FILE: '受管结构文件',
  HTTP: 'HTTP 接口',
  DOCUMENT: '文档数据库',
  KEY_VALUE: '键值数据库',
  WIDE_COLUMN: '宽列数据库',
  GRAPH: '图数据库',
}
interface ConnectionField {
  key: string
  label: string
  secret?: boolean
  required?: boolean
}
/** 源配置从不读取旧连接；这些字段只用于创建或显式整体替换加密配置。 */
export const connectionFields: Record<SourceKind, ConnectionField[]> = {
  RELATIONAL: [
    { key: 'url', label: 'JDBC 地址', secret: true, required: true },
    { key: 'user', label: '用户名', required: true },
    { key: 'password', label: '密码', secret: true },
  ],
  WAREHOUSE: [
    { key: 'url', label: 'JDBC 地址', secret: true, required: true },
    { key: 'user', label: '用户名', required: true },
    { key: 'password', label: '密码', secret: true },
  ],
  LAKE: [
    { key: 'endpoint', label: 'S3 / MinIO 地址', required: true },
    { key: 'bucket', label: '存储桶', required: true },
    { key: 'accessKey', label: '访问密钥', secret: true, required: true },
    { key: 'secretKey', label: '秘密密钥', secret: true, required: true },
  ],
  FILE: [{ key: 'fileId', label: '受管文件 ID', required: true }],
  HTTP: [
    { key: 'baseUrl', label: '接口根地址', required: true },
    { key: 'pageParam', label: '分页参数名' },
  ],
  DOCUMENT: [
    { key: 'uri', label: 'MongoDB 连接 URI', secret: true, required: true },
    { key: 'database', label: '数据库', required: true },
  ],
  KEY_VALUE: [
    { key: 'host', label: '主机', required: true },
    { key: 'port', label: '端口', required: true },
    { key: 'database', label: '数据库编号', required: true },
    { key: 'password', label: '密码', secret: true },
  ],
  WIDE_COLUMN: [
    { key: 'contactPoints', label: '联系节点（每行一个）', required: true },
    { key: 'port', label: '端口', required: true },
    { key: 'datacenter', label: '数据中心', required: true },
    { key: 'keyspace', label: '键空间', required: true },
    { key: 'user', label: '用户名' },
    { key: 'password', label: '密码', secret: true },
  ],
  GRAPH: [
    { key: 'uri', label: 'Neo4j 连接 URI', secret: true, required: true },
    { key: 'database', label: '数据库', required: true },
    { key: 'user', label: '用户名', required: true },
    { key: 'password', label: '密码', secret: true },
  ],
}
export const dialects: Partial<Record<SourceKind, string[]>> = {
  RELATIONAL: ['postgresql'],
  WAREHOUSE: ['clickhouse'],
  LAKE: ['s3-parquet'],
  FILE: ['csv', 'json', 'excel'],
  HTTP: ['json'],
  DOCUMENT: ['mongodb'],
  KEY_VALUE: ['redis'],
  WIDE_COLUMN: ['cassandra'],
  GRAPH: ['neo4j'],
}
export function lines(text: string) {
  return text
    .split(/[\n,]/)
    .map((item) => item.trim())
    .filter(Boolean)
}
export function objectJson(text: string, field: string): Record<string, unknown> | null {
  if (!text.trim()) return null
  let value: unknown
  // JSON.parse 的原始异常可能包含秘密输入片段，必须在解析边界统一替换。
  try {
    value = JSON.parse(text)
  } catch {
    throw new Error(`${field}必须是有效 JSON 对象。`)
  }
  if (!value || Array.isArray(value) || typeof value !== 'object')
    throw new Error(`${field}必须是有效 JSON 对象。`)
  return value as Record<string, unknown>
}
