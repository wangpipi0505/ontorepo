<script setup lang="ts">
/** 列表和详情显示管理状态；弃用或损坏版本不开放消费制品入口。 */
import { computed, ref, watch } from 'vue'
import type { Space } from '@onto/api-client'
import type { Release, ReleaseDetail } from '../../../../../packages/api-client/src/release'
import { useWorkspace } from '../../stores/workspace'
import { useSession } from '../../stores/session'
import { useResource } from '../../composables/useResource'
import { useMutation, confirmAction } from '../../composables/useMutation'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import ArtifactList from './ArtifactList.vue'
import ReleaseEntities from './ReleaseEntities.vue'
import ReleaseDiffPanel from './ReleaseDiffPanel.vue'
import RestoreDraftPanel from './RestoreDraftPanel.vue'
import './p4.css'
const workspace = useWorkspace(),
  api = useSession().api,
  mutation = useMutation()
const releases = useResource<Release[]>([]),
  detail = useResource<ReleaseDetail | null>(null),
  document = useResource<unknown | null>(null)
const selected = ref(''),
  reason = ref(''),
  restoring = ref(false)
const owner = computed(() => workspace.space?.role === 'OWNER' && workspace.canWrite)
let generation = 0
async function load() {
  const id = workspace.space?.id,
    context = workspace.captureContext()
  if (!id || mutation.pending.value || restoring.value) return
  let currentSpace: Space | undefined
  const result = await releases.load(async () => {
    const [items, space] = await Promise.all([
      api.get<Release[]>(`/releases?spaceId=${id}`),
      api.get<Space>(`/spaces/${id}`),
    ])
    currentSpace = space
    return items
  })
  // 关闭旧确认后完整刷新才解除冲突；失败、卸载或切空间的迟到结果不能解锁写入。
  if (result && currentSpace && workspace.isCurrentContext(context)) {
    workspace.refreshed(currentSpace)
    if (!result.some((item) => item.id === selected.value)) selected.value = result[0]?.id ?? ''
    else await loadDetail()
  }
}
function loadDetail() {
  if (!selected.value) return
  const id = selected.value
  return detail.load(async () => {
    const value = await api.get<ReleaseDetail>(`/releases/${id}`)
    if (value.release.id !== id) throw new Error('版本详情与当前选择不符。')
    return value
  })
}
watch(selected, () => {
  generation++
  detail.reset()
  document.reset()
  reason.value = ''
  restoring.value = false
  void loadDetail()
})
watch(
  () => workspace.space?.id,
  () => {
    selected.value = ''
    releases.reset()
    detail.reset()
    void load()
  },
  { immediate: true },
)
async function action(kind: 'deprecate' | 'integrity-check') {
  const value = detail.data.value,
    ticket = generation,
    context = workspace.captureContext()
  if (
    !value ||
    !owner.value ||
    (kind === 'deprecate' && (value.release.status !== 'READY' || !reason.value.trim()))
  )
    return
  const releaseId = value.release.id
  if (
    kind === 'deprecate' &&
    (!(await confirmAction(`弃用 v${value.release.versionNo} 后消费者不能继续调用。确认弃用？`)) ||
      !workspace.isCurrentContext(context) ||
      ticket !== generation)
  )
    return
  await mutation.run(async ({ isCurrent }) => {
    const updated = await api.post<ReleaseDetail>(
      `/releases/${releaseId}/${kind}`,
      kind === 'deprecate' ? { reason: reason.value.trim() } : {},
    )
    if (!isCurrent() || ticket !== generation) return
    if (updated.release.id !== releaseId) throw new Error('版本操作返回了不同版本，请重新读取。')
    detail.data.value = updated
    document.reset()
    releases.data.value = releases.data.value.map((item) =>
      item.id === releaseId ? updated.release : item,
    )
  })
}
function readDocument(kind: 'manifest' | 'context-pack') {
  if (detail.data.value?.release.status !== 'READY') return
  return document.load(() => api.get<unknown>(`/releases/${selected.value}/${kind}`))
}
async function restored() {
  restoring.value = false
  await load()
}
</script>
<template>
  <div class="p4-page stack">
    <div class="page-heading">
      <div>
        <h1>正式版本</h1>
        <p class="muted">查看版本制品、冻结实体、版本差异与完整性。</p>
      </div>
      <el-button :disabled="mutation.pending.value || restoring" @click="load">刷新版本</el-button>
    </div>
    <p v-if="mutation.error.value" class="error-text" role="alert">{{ mutation.error.value }}</p>
    <AsyncState
      :loading="releases.loading.value"
      :error="releases.error.value"
      :empty="!releases.data.value.length"
      empty-text="尚无正式版本。请在候选发布中完成五门禁与制品准备。"
      @retry="load"
    >
      <div class="split">
        <section class="panel stack">
          <h2>版本目录</h2>
          <button
            v-for="item in releases.data.value"
            :key="item.id"
            class="selector record"
            :aria-pressed="selected === item.id"
            :disabled="mutation.pending.value || restoring"
            @click="selected = item.id"
          >
            <b>v{{ item.versionNo }} · {{ item.status }}</b>
            <p class="muted">{{ item.publishedAt }}</p>
            <span class="identity">{{ item.bundleSha256 }}</span>
          </button>
        </section>
        <AsyncState :loading="detail.loading.value" :error="detail.error.value" @retry="loadDetail">
          <section v-if="detail.data.value" class="panel stack">
            <h2>
              版本 v{{ detail.data.value.release.versionNo }} ·
              {{ detail.data.value.release.status }}
            </h2>
            <p v-if="detail.data.value.release.status !== 'READY'" class="readonly-note">
              当前版本不可消费；历史事实仍可管理浏览。
            </p>
            <dl class="details-grid">
              <dt>版本标识</dt>
              <dd>{{ detail.data.value.release.id }}</dd>
              <dt>发布人</dt>
              <dd>{{ detail.data.value.release.publishedBy }}</dd>
              <dt>发布时间</dt>
              <dd>{{ detail.data.value.release.publishedAt }}</dd>
            </dl>
            <div class="row">
              <RouterLink
                :to="`/spaces/${workspace.space?.id}/consumption?releaseId=${detail.data.value.release.id}`"
              >
                去服务消费 →
              </RouterLink>
              <RouterLink
                :to="`/spaces/${workspace.space?.id}/events?releaseId=${detail.data.value.release.id}`"
              >
                查看事件 →
              </RouterLink>
              <RouterLink
                :to="`/spaces/${workspace.space?.id}/audit?subjectType=RELEASE&subjectId=${detail.data.value.release.id}`"
              >
                查看审计 →
              </RouterLink>
            </div>
            <ArtifactList :artifacts="detail.data.value.artifacts" />
            <details>
              <summary>冻结门禁报告</summary>
              <article v-for="check in detail.data.value.checks" :key="check.kind" class="record">
                <b>{{ check.kind }} · {{ check.conclusion }}</b>
                <p>{{ check.reason }}</p>
                <pre>{{ JSON.stringify(check.report, null, 2) }}</pre>
              </article>
            </details>
            <details>
              <summary>正式服务端点 · {{ detail.data.value.endpoints.length }}</summary>
              <p
                v-for="endpoint in detail.data.value.endpoints"
                :key="endpoint.id"
                class="identity"
              >
                {{ endpoint.opCode }} · {{ endpoint.status }}
                <br />
                {{ endpoint.route }}
              </p>
            </details>
            <div class="row">
              <el-button
                :disabled="detail.data.value.release.status !== 'READY'"
                @click="readDocument('manifest')"
              >
                读取正式操作清单
              </el-button>
              <el-button
                :disabled="detail.data.value.release.status !== 'READY'"
                @click="readDocument('context-pack')"
              >
                读取正式上下文包
              </el-button>
            </div>
            <p v-if="document.error.value" class="error-text" role="alert">
              {{ document.error.value }}
            </p>
            <pre v-if="document.data.value">{{ JSON.stringify(document.data.value, null, 2) }}</pre>
            <ReleaseEntities :key="selected" :release-id="selected" />
            <template v-if="owner">
              <label>
                弃用理由
                <textarea
                  v-model="reason"
                  rows="2"
                  :disabled="mutation.pending.value || restoring"
                />
              </label>
              <div class="row">
                <el-button
                  :disabled="mutation.pending.value || restoring"
                  @click="action('integrity-check')"
                >
                  执行真实完整性检查
                </el-button>
                <el-button
                  type="danger"
                  :disabled="
                    mutation.pending.value ||
                    restoring ||
                    detail.data.value.release.status !== 'READY' ||
                    !reason.trim()
                  "
                  @click="action('deprecate')"
                >
                  弃用此版本
                </el-button>
                <el-button
                  :disabled="
                    mutation.pending.value ||
                    restoring ||
                    detail.data.value.release.status === 'CORRUPTED'
                  "
                  @click="restoring = true"
                >
                  恢复到草稿
                </el-button>
              </div>
            </template>
          </section>
        </AsyncState>
      </div>
      <RestoreDraftPanel
        v-if="restoring && detail.data.value"
        :key="selected"
        :release="detail.data.value.release"
        @cancel="restoring = false"
        @restored="restored"
      />
      <ReleaseDiffPanel
        v-if="workspace.space"
        :releases="releases.data.value"
        :space-id="workspace.space.id"
      />
    </AsyncState>
  </div>
</template>
