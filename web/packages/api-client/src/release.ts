import type { Candidate, Execution } from './types'
import type { JsonObject } from './supply'

/** P4 固定展示 DTO；制品只暴露安全摘要，凭据目录永远不包含明文 key。 */
export type PublicationStatus =
  | 'FREEZING'
  | 'FROZEN'
  | 'VALIDATING'
  | 'TESTING'
  | 'REVIEWING'
  | 'READY_TO_COMMIT'
  | 'COMMITTED'
  | 'FAILED'
  | 'CANCELLED'
export type PublicationCandidate = Omit<Candidate, 'status'> & {
  status: PublicationStatus
  preReleaseId?: string | null
  preVersionNo?: number | null
  bundleSha256?: string | null
}
export type GateKind = 'CONFLICT' | 'CONSISTENCY' | 'CONTRACT' | 'TEST' | 'REVIEW'
export interface PublicationCheck {
  kind: GateKind
  conclusion: 'PASSED' | 'FAILED' | 'NOT_CHECKED'
  reason: string | null
  candidateSha256: string
  checkedAt: string | null
  report: JsonObject
}
export interface ReleaseArtifact {
  kind: 'BUNDLE' | 'MANIFEST' | 'CONTEXT_PACK' | 'KNOWLEDGE_INDEX'
  sha256: string | null
  size: number | null
  ready: boolean
}
export interface Publication {
  capability: 'CONTRACT' | 'MODEL_ONLY' | null
  candidateId: string
  candidateSha256: string
  draftRev: number
  status: PublicationStatus
  preReleaseId: string | null
  preVersionNo: number | null
  checks: PublicationCheck[]
  waivers: {
    candidateSha256: string
    reason: string
    actorId: string
    createdAt: string
    [key: string]: unknown
  }[]
  artifacts: ReleaseArtifact[]
  review: { actorId: string; reason: string; reviewedAt: string } | null
  releaseId: string | null
}
export interface Release {
  id: string
  spaceId: string
  versionNo: number
  candidateId: string
  status: 'READY' | 'DEPRECATED' | 'CORRUPTED'
  bundleSha256: string
  manifestSha256: string
  publishedAt: string
  publishedBy: string
}
export interface Endpoint {
  id: string
  releaseId: string
  opCode: string
  route: string
  description?: string
  status: 'ACTIVE' | 'DISABLED'
  execAttrs: JsonObject
}
export interface ReleaseDetail {
  release: Release
  artifacts: ReleaseArtifact[]
  endpoints: Endpoint[]
  checks: PublicationCheck[]
}
export type ReleaseEntityKind =
  | 'CLASS'
  | 'PROPERTY'
  | 'RELATION'
  | 'OPERATION'
  | 'RULE'
  | 'STRATEGY'
  | 'KNOWLEDGE'
  | 'MAPPING'
export interface ReleaseEntity {
  kind: ReleaseEntityKind
  key: string
  value: JsonObject
}
export interface ReleaseDiffItem {
  kind: string
  key: string
  before?: unknown
  after?: unknown
}
export interface ReleaseDiff {
  fromReleaseId?: string | null
  toReleaseId?: string | null
  added: ReleaseDiffItem[]
  removed: ReleaseDiffItem[]
  changed: ReleaseDiffItem[]
}
export interface EndpointGrant {
  userId: string
  username: string
  displayName: string
  dataScope: JsonObject | null
  expiresAt: string | null
  status: 'ACTIVE' | 'REVOKED'
}
export interface EndpointRoleGrant {
  roleCode: string
  roleName: string
  dataScope: JsonObject | null
  expiresAt: string | null
  status: 'ACTIVE' | 'REVOKED'
}
export interface ApiCredential {
  id: string
  spaceId: string
  principalId: string
  name: string
  status: 'ACTIVE' | 'REVOKED'
  createdAt: string
  expiresAt: string | null
}
export interface ReleaseEvent {
  id: number
  spaceId: string
  releaseId: string
  type: string
  boundStatus: string
  releaseStatus: Release['status']
  status: 'PENDING' | 'SENT' | 'DEAD'
  retryCount: number
  nextRetryAt: string | null
  lastError: string | null
  createdAt: string
}
export interface EventReceipt {
  eventId: number
  releaseId: string
  type: string
  receivedAt: string
  receipt: JsonObject
}
/** 旧记录缺失的字段保持 null；不能补造授权或执行时间。 */
export type TracedExecution = Omit<Execution, 'subjectType' | 'subjectId'> & {
  subjectType: 'CANDIDATE' | 'RELEASE'
  subjectId: string | null
  caller: string | null
}
export interface TraceGraphData {
  nodes: { id: string; label: string; kind: string; ref?: string | null }[]
  edges: { id: string; from: string; to: string; label: string }[]
}
export interface ExecutionTrace {
  execution: TracedExecution
  authorization: { order: number; kind: string; passed: boolean | null; detail: unknown }[]
  steps: Execution['steps']
  evidence: {
    id: string
    type: string
    ref: string | null
    sha256: string | null
    detail: JsonObject
  }[]
  timeline: { id: string; at: string | null; kind: string; title: string; detail: unknown }[]
  graph: TraceGraphData
}
export interface AuditPage {
  items: {
    id: number
    spaceId: string
    actorId: string
    actorName?: string | null
    action: string
    subjectId: string | null
    subjectType?: string | null
    versionRefs?: unknown
    inputDigest?: string | null
    outputDigest?: string | null
    result?: string | null
    detail: unknown
    createdAt: string
  }[]
  nextBeforeId: number | null
}
export interface ReleaseInvocationMeta {
  spaceCode: string
  releaseId: string
  version: string
  contentDigest: string
  preview: false
}
