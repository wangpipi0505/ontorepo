<script setup lang="ts">
/** 供给只读取固定候选。每次读取核对候选标识和摘要，不以草稿或本地 contains 补结果。 */
import type {
  Candidate,
  CandidateIdentity,
  CandidateContracts,
  ContextPack,
  KnowledgeSearch,
} from '@onto/api-client'
import { ApiError } from '@onto/api-client'
import { ref, watch } from 'vue'
import { useSession } from '../../stores/session'
import { useResource } from '../../composables/useResource'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { verifyManifestPreview } from './candidateIdentity'
const props = defineProps<{ candidate: Candidate }>(),
  api = useSession().api
const contracts = useResource<CandidateContracts | null>(null),
  pack = useResource<ContextPack | null>(null),
  search = useResource<KnowledgeSearch | null>(null)
const query = ref('')
function verify<T extends CandidateIdentity>(result: T, id: string, sha: string): T {
  if (result.candidateId !== id || result.candidateSha256 !== sha)
    throw new ApiError(
      'CANDIDATE_HASH_MISMATCH',
      '供给内容的候选标识或摘要不符，请重新读取候选。',
      409,
    )
  return result
}
async function readContracts() {
  const { id, sha256 } = props.candidate
  await contracts.load(async () => {
    const result = verify(
      await api.get<CandidateContracts>(`/candidates/${id}/contracts`),
      id,
      sha256,
    )
    result.manifests.forEach((manifest) => verifyManifestPreview(manifest.ontology_meta))
    return result
  })
}
async function readPack() {
  const { id, sha256 } = props.candidate
  return pack.load(async () =>
    verify(await api.get<ContextPack>(`/candidates/${id}/context-pack`), id, sha256),
  )
}
async function runSearch() {
  const { id, sha256 } = props.candidate,
    text = query.value.trim()
  if (!text) {
    search.error.value = '请输入搜索词。'
    return
  }
  await search.load(async () => {
    // 来源信息按同一冻结包的 entryId 关联；候选检索接口只返回命中与真实分数。
    if (!pack.data.value && !(await readPack()))
      throw new Error('知识包读取失败，无法核对搜索来源。')
    return verify(
      await api.get<KnowledgeSearch>(
        `/candidates/${id}/knowledge-search?q=${encodeURIComponent(text)}&limit=10`,
      ),
      id,
      sha256,
    )
  })
}
function source(entryId: string) {
  const item = pack.data.value?.termDictionary.find(
    (item) => item.id === entryId || item.entryId === entryId,
  )
  return typeof item?.sourceRef === 'string' ? item.sourceRef : '冻结知识包未提供来源'
}
watch(
  () => `${props.candidate.id}:${props.candidate.sha256}`,
  () => {
    contracts.reset()
    pack.reset()
    search.reset()
    query.value = ''
  },
)
</script>
<template>
  <section class="stack">
    <div class="readonly-note">候选供给预览 · preview: true。此内容不能作为正式发布版本消费。</div>
    <dl class="details-grid">
      <dt>候选摘要</dt>
      <dd class="mono">{{ candidate.sha256 }}</dd>
    </dl>
    <section class="panel stack">
      <div class="row spread">
        <h2>契约预览</h2>
        <el-button :loading="contracts.loading.value" @click="readContracts">
          读取契约预览
        </el-button>
      </div>
      <AsyncState
        :loading="contracts.loading.value"
        :error="contracts.error.value"
        @retry="readContracts"
      >
        <template v-if="contracts.data.value">
          <p v-if="!contracts.data.value.manifests.length" class="muted">
            此候选没有可生成的操作契约。
          </p>
          <details v-for="(manifest, index) in contracts.data.value.manifests" :key="index" open>
            <summary>{{ manifest.tool_code || `操作 ${index + 1}` }} · manifest_preview</summary>
            <pre class="supply-json">{{ JSON.stringify(manifest, null, 2) }}</pre>
          </details>
        </template>
        <p v-else class="muted">
          从冻结声明生成完整预览。任何操作声明不完整，服务端会返回字段错误。
        </p>
      </AsyncState>
    </section>
    <section class="panel stack">
      <div class="row spread">
        <h2>知识包</h2>
        <el-button :loading="pack.loading.value" @click="readPack">读取知识包</el-button>
      </div>
      <AsyncState :loading="pack.loading.value" :error="pack.error.value" @retry="readPack">
        <template v-if="pack.data.value">
          <details
            v-for="section in [
              { key: 'conceptCards', label: '概念卡' },
              { key: 'termDictionary', label: '术语词典' },
              { key: 'relationSummaries', label: '关系摘要' },
              { key: 'exampleQa', label: '示例问答' },
            ] as const"
            :key="section.key"
            open
          >
            <summary>{{ section.label }} · {{ pack.data.value[section.key].length }}</summary>
            <pre class="supply-json">{{
              JSON.stringify(pack.data.value[section.key], null, 2)
            }}</pre>
          </details>
        </template>
      </AsyncState>
      <el-form data-search="knowledge" label-position="top" @submit.prevent="runSearch">
        <el-form-item label="搜索冻结知识">
          <el-input v-model="query" placeholder="输入术语或别名" />
        </el-form-item>
        <el-button native-type="submit" type="primary" :loading="search.loading.value">
          搜索
        </el-button>
      </el-form>
      <AsyncState :loading="search.loading.value" :error="search.error.value" @retry="runSearch">
        <p v-if="search.data.value && !search.data.value.hits.length" class="muted">
          此候选中没有匹配知识。
        </p>
        <article
          v-for="hit in search.data.value?.hits || []"
          :key="hit.entryId"
          class="knowledge-hit"
        >
          <div class="row spread">
            <b>{{ hit.term }}</b>
            <span class="pill">分数 {{ hit.score }}</span>
          </div>
          <p>{{ hit.definition }}</p>
          <p class="muted">
            匹配别名：{{ hit.matchedAlias || '—' }} · 来源：{{ source(hit.entryId) }}
          </p>
          <span class="mono muted">{{ hit.entryId }}</span>
        </article>
      </AsyncState>
    </section>
  </section>
</template>
<style scoped>
.supply-json {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
  max-width: 100%;
  min-width: 0;
  padding: 14px;
  background: var(--canvas);
  font:
    12px/1.7 Consolas,
    monospace;
}
details {
  min-width: 0;
}
summary {
  cursor: pointer;
  overflow-wrap: anywhere;
}
.knowledge-hit {
  padding: 16px 0;
  border-top: 1px solid var(--line);
  overflow-wrap: anywhere;
}
.knowledge-hit p {
  margin: 10px 0;
}
</style>
