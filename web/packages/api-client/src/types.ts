import type { components } from './generated'

/** 结构来自真实服务导出的 OpenAPI；业务契约进一步收紧必填、枚举及可空字段。 */
type Schema<Key extends keyof components['schemas']> = Required<components['schemas'][Key]>
export type User = Schema<'User'>
export type LoginResult = Omit<Schema<'Login'>, 'user'> & { user: User }
export type Space = Omit<Schema<'Space'>, 'status' | 'role'> & {
  status: 'ACTIVE' | 'ARCHIVED'
  role: 'OWNER' | 'EDITOR' | 'VIEWER'
  description?: string
  updatedAt?: string
}
export type OntClass = Omit<Schema<'OntClass'>, 'parentId'> & { parentId: string | null }
export type RangeType = 'STRING' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'DATE'
export type Scalar = string | number | boolean
export interface PropertyFacets {
  unitCodebookId?: string
  unitCode?: string
  codebookId?: string
  defaultValue?: Scalar
  enumValues?: Scalar[]
  minimum?: number
  maximum?: number
  primaryKey?: boolean
}
export type OntProperty = Omit<Schema<'OntProperty'>, 'rangeType'> & {
  rangeType: RangeType
  displayName?: string
  facets?: PropertyFacets
}
export type OperationKind = 'OBJECT_QUERY' | 'RELATION_QUERY' | 'RULE_CHECK' | 'SCORING'
export type Operation = Omit<Schema<'Operation'>, 'kind'> & {
  kind: OperationKind
  description?: string
  precondition?: string
  strategyRef?: string | null
  ruleIds?: string[]
}
/** P1 扩展类型由 contracts/p1-extension.md 约束，生成文档更新前显式维护新增结构。 */
export interface Codebook {
  id: string
  spaceId: string
  code: string
  name: string
  kind: 'UNIT' | 'VALUE'
  entries: { code: string; label: string }[]
}
export interface Relation {
  id: string
  spaceId: string
  name: string
  fromClassId: string
  toClassId: string
  cardinality: 'ONE_TO_ONE' | 'ONE_TO_MANY' | 'MANY_TO_ONE' | 'MANY_TO_MANY'
  inverseOf: string | null
  transitive: boolean
  symmetric: boolean
  description: string
  /** 服务端计算的推理子集标记，仅展示；关系写请求不允许客户端指定。 */
  readonly subsetExceeded?: boolean
}
export interface Constraint {
  id: string
  spaceId: string
  name: string
  kind: 'RANGE' | 'CARDINALITY' | 'MUTEX' | 'DEPENDENCY'
  targetType: 'CLASS' | 'PROPERTY' | 'RELATION'
  targetRef: string
  expr: Record<string, unknown>
}
export interface Rule {
  id: string
  spaceId: string
  name: string
  applicableClassId: string
  kind: 'VALIDATION' | 'SCORING'
  condition: string
  action: Record<string, unknown>
  scoring: Record<string, unknown> | null
  severity: 'WARN' | 'ERROR'
}
export interface Strategy {
  id: string
  spaceId: string
  name: string
  policy: {
    maxRows: number
    timeoutMs: number
    resultLimit: number
    failureMode: 'FAIL'
    readScope: Record<string, unknown> | null
  }
  boundOps: string[]
}
export interface Conflict {
  kind: 'REFERENCE' | 'TYPE' | 'CONSTRAINT' | 'LOGIC'
  message: string
  entityIds: string[]
}
export interface Conflicts {
  errors: Conflict[]
  warnings: Conflict[]
}
export interface ImportIssue {
  line: number
  message: string
  construct?: string
}
export interface ImportPreview {
  id: string
  status: 'PREVIEW' | 'FAILED'
  targetDraftRev: number
  added: number
  updated: number
  conflicts: ImportIssue[]
  unsupported: ImportIssue[]
  errors: ImportIssue[]
}
export interface TestCase {
  id: string
  spaceId: string
  name: string
  question: string
  operationId: string
  /** 操作输入根类型由冻结 Schema 决定，可以是数组、标量或 null。 */
  input: unknown
  expected: Record<string, unknown>
  matchMode: 'EXACT' | 'CONTAINS'
  enabled: boolean
  /** 旧用例缺省为 DIRECT；NL 由服务端匹配真实操作，operationId 仅作为期望断言。 */
  mode?: 'DIRECT' | 'NL'
}
export interface CandidateCheck {
  kind: string
  conclusion: string
  reason: string
  message: string
  details: Record<string, unknown>
}
export interface CandidateChecks {
  candidateSha256: string
  eligible: boolean
  checks: CandidateCheck[]
}
export interface CandidateTests {
  candidateSha256: string
  passed: boolean
  results: { caseId: string; name: string; passed: boolean; executionId: string; message: string }[]
}
export interface Waiver {
  id: string
  candidateSha256: string
  kind: 'SUBSET_EXCEEDED'
  reason: string
  approver: string
}
/** 候选只可试执行，不能作为正式发布版本消费。 */
export type Candidate = Omit<Schema<'Candidate'>, 'status'> & { status: 'TESTING' }
/** 当前 springdoc 字符串可空标记不完整，以下按已批准契约明确 null 语义。 */
export type Execution = Omit<
  Schema<'Execution'>,
  'subjectType' | 'status' | 'errorCode' | 'rows' | 'steps'
> & {
  subjectType: 'CANDIDATE'
  status: 'SUCCEEDED' | 'FAILED'
  errorCode: string | null
  rows: Record<string, string | null>[]
  steps: Schema<'Step'>[]
}
