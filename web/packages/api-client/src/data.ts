import type { JsonObject } from './supply'
import type { Space } from './types'

/** P3 契约独立维护；所有来源标识均由当前空间的服务端读取提供。 */
export type SourceKind =
  | 'RELATIONAL'
  | 'WAREHOUSE'
  | 'LAKE'
  | 'FILE'
  | 'HTTP'
  | 'DOCUMENT'
  | 'KEY_VALUE'
  | 'WIDE_COLUMN'
  | 'GRAPH'
export interface Datasource {
  id: string
  spaceId: string
  code: string
  name: string
  kind: SourceKind
  dialect: string
  accessScope: string[]
  status: string
  lastTestAt: string | null
  description?: string
  tenant?: string | null
  ownerId?: string | null
}
export interface SourceWrite {
  code: string
  name: string
  kind: SourceKind
  connection: JsonObject | null
  accessScope: string[]
  description?: string
  tenant?: string
  ownerId?: string | null
}
export interface SourceCapability {
  kind: SourceKind
  dialect: string
  implemented: boolean
  verifiedLocally: boolean
  capabilities: string[]
  limits: string[]
  verificationNote: string
}
export interface SourceProbe {
  stages: {
    stage: 'CONNECTIVITY' | 'AUTHENTICATION' | 'SCOPE'
    status: 'PASSED' | 'FAILED' | 'NOT_RUN'
    code: string
    message: string
  }[]
  testedAt: string
}
export interface MetadataResource {
  name: string
  fields: { name: string; type: string; nullable: boolean }[]
  primaryKey: string[]
  relations: { fromColumn: string; targetResource: string; targetColumn: string }[]
  indexes: { name: string; columns: string[]; unique: boolean }[]
}
export interface MetadataSummary {
  id: string
  sha256: string
  capturedAt: string
}
export interface MetadataSnapshot extends MetadataSummary {
  resources: MetadataResource[]
}
export interface MetadataDiff {
  added: MetadataChange[]
  removed: MetadataChange[]
  changed: MetadataChange[]
  affectedMappings: {
    id: string
    kind: 'OBJECT' | 'PROPERTY' | 'RELATION'
    reason: string
    operationIds: string[]
  }[]
}
export interface MetadataChange {
  path: string
  kind: 'RESOURCE' | 'FIELD' | 'PRIMARY_KEY' | 'RELATION' | 'INDEX'
  before?: unknown
  after?: unknown
}
export interface SourceSync {
  space: Space
  snapshot: MetadataSnapshot
  diff: MetadataDiff
}
export interface ManagedFile {
  id: string
  name: string
  mediaType: string
  size: number
  sha256: string
  resource: string
}
export interface ObjectMapping {
  id: string
  datasourceId: string
  sourceResource: string
  targetClassId: string
  objectKeyExpr: string
  filter: JsonObject | null
  metaSnapshotId: string
}
export interface PropertyMapping {
  id: string
  objectMappingId: string
  sourceField: string
  targetPropertyId: string
  transform: {
    kind: 'IDENTITY' | 'TO_STRING' | 'TO_NUMBER' | 'TO_BOOLEAN' | 'CODEBOOK'
    codebookId?: string
  } | null
  required: boolean
}
export interface RelationMapping {
  id: string
  datasourceId: string
  relationId: string
  fromObjectMappingId: string
  toObjectMappingId: string
  fromKeyExpr: string
  toKeyExpr: string
  join: { fromField: string; toField: string }
  cardinalityCheck: boolean
  metaSnapshotId: string
}
export interface MappingCollection {
  objects: ObjectMapping[]
  properties: PropertyMapping[]
  relations: RelationMapping[]
}
export type MappingKind = keyof MappingCollection
export type Mapping = ObjectMapping | PropertyMapping | RelationMapping
export interface MappingValidation {
  valid: boolean
  checks: {
    kind: 'CLASS_OBJECT' | 'REQUIRED_PROPERTY' | 'RELATION_KEYS' | 'REACHABLE_REFERENCE'
    passed: boolean
    issues: MappingIssue[]
  }[]
  coverage: { mapped: number; pending: number; unmappable: number }
  coverageBasis: string
  affectedOperations: { id: string; code: string; name: string }[]
}
export interface MappingIssue {
  code: string
  message: string
  entityId?: string
  field?: string
}
export interface MappingImpact {
  operations: { id: string; code: string; name: string }[]
  issues: MappingIssue[]
}
export interface DataQuery {
  mode: 'OBJECT' | 'RELATION'
  objectMappingId: string
  relationMappingId?: string
  propertyIds: string[]
  filters: {
    propertyId: string
    operator: 'EQ' | 'IN' | 'GT' | 'GE' | 'LT' | 'LE'
    inputParam: string
  }[]
}
export interface OperationDataQuery {
  operationId: string
  dataQuery: DataQuery | null
}
export interface Suggestion {
  id: string
  batchId: string
  sourceKind: 'TABLE' | 'FILE' | 'DOCUMENT'
  kind: 'CLASS' | 'PROPERTY' | 'RELATION'
  payload: JsonObject
  confidence: number
  location: JsonObject
  status: 'PENDING' | 'ACCEPTED' | 'REJECTED'
}
export interface SuggestionDecision {
  id: string
  decision: 'ACCEPT' | 'REJECT'
  payload?: JsonObject
}
export interface ExtractionRequest {
  sourceKind: 'TABLE' | 'FILE' | 'DOCUMENT'
  datasourceId?: string
  metaSnapshotId?: string
  resource?: string
  fileId?: string
}
export interface CatalogFieldBinding {
  objectMappingId: string
  classId: string
  className: string
  propertyId: string
  propertyName: string
}
/** 目录读取视图保留服务端提供的来源和关联，不推造未定义的统计分母。 */
export interface DataCatalog {
  datasources: (Datasource & {
    latestSnapshotId: string | null
    mappingCount: number
    classIds: string[]
    operationIds: string[]
    resources?: {
      name: string
      fields: { name: string; type: string; bindings: CatalogFieldBinding[] }[]
    }[]
  })[]
  bindings: {
    datasourceId: string
    objectMappingId: string
    resource: string
    classId: string
    className: string
    propertyCount: number
    relationCount: number
    operationIds: string[]
  }[]
}
