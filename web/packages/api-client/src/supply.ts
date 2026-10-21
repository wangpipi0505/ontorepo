/** P2 原始协议类型；assistantTool 内部 snake_case 不做字段翻译。 */
export type JsonObject = Record<string, unknown>
/** JSON 值保持根类型与嵌套值；参数示例是这些值的数组，不限对象根。 */
export type JsonValue =
  | string
  | number
  | boolean
  | null
  | JsonValue[]
  | { [key: string]: JsonValue }
export interface AssistantTool {
  name?: string
  description?: string
  guard_attrs?: string[]
  info_attrs?: string[]
  param_examples?: JsonValue[]
  exec?: { readonly: boolean; side_effect: boolean; idempotent: boolean; cancellable: boolean }
  risk_level?: 'READ_ONLY' | 'CONFIRM_REQUIRED'
  error_semantics?: { match: string; semantic: string }[]
  return_spec?: JsonObject
}
export interface AssistantDeclaration {
  operationId: string
  inputSchema: JsonObject | null
  outputSchema: JsonObject | null
  assistantTool: AssistantTool | null
}
export type GuardType = 'STRING' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN'
/** table 是各协议的原始资源标识，服务端须与冻结元数据精确匹配；column 为单列名。 */
export interface GuardFilterBinding {
  table: string
  column: string
  operator: 'IN' | 'EQ'
}
export interface GuardDefinition {
  type: GuardType
  authorization_rule?: { kind: 'PRINCIPAL_ATTRIBUTE'; attribute: string }
  /** 旧单项与 filter_bindings 互斥；主体规则可与任一源过滤形状同时存在。 */
  filter_binding?: GuardFilterBinding
  filter_bindings?: GuardFilterBinding[]
}
export interface Guards {
  guardCfg: Record<string, GuardDefinition>
}
export interface GrantUser {
  userId: string
  username: string
  displayName: string
}
export interface SpaceGrant extends GrantUser {
  role: 'OWNER' | 'EDITOR' | 'VIEWER'
  dataScope: JsonObject | null
  attributes: JsonObject
}
export interface KnowledgeEntry {
  id: string
  spaceId: string
  kind?: 'TERM' | 'ENTITY' | 'RELATION'
  term: string
  aliases: string[]
  definition: string
  conceptRef: string | null
  sourceRef: string
  subjectRef?: string | null
  predicate?: string | null
  objectRef?: string | null
}
export interface CandidateIdentity {
  candidateId: string
  candidateSha256: string
}
export interface CandidateContracts extends CandidateIdentity {
  manifests: JsonObject[]
}
export interface ContextPack extends CandidateIdentity {
  conceptCards: JsonObject[]
  termDictionary: JsonObject[]
  relationSummaries: JsonObject[]
  exampleQa: JsonObject[]
}
export interface KnowledgeSearch extends CandidateIdentity {
  hits: {
    entryId: string
    term: string
    definition: string
    score: number
    matchedAlias: string | null
  }[]
}
