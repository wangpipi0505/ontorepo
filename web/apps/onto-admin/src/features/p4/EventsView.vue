<script setup lang="ts">
/** 事件绑定状态与版本当前状态分开展示；重放只提交原事件 id。 */
import { computed, inject, reactive, watch } from 'vue'
import { routeLocationKey } from 'vue-router'
import type { ReleaseEvent, EventReceipt } from '../../../../../packages/api-client/src/release'
import { useWorkspace } from '../../stores/workspace'
import { useSession } from '../../stores/session'
import { useResource } from '../../composables/useResource'
import { useMutation } from '../../composables/useMutation'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import './p4.css'
const route = inject(routeLocationKey, undefined),
  workspace = useWorkspace(),
  api = useSession().api,
  mutation = useMutation()
const state = useResource<{ events: ReleaseEvent[]; inbox: EventReceipt[] } | null>(null)
const reasons = reactive<Record<number, string>>({})
const releaseId = computed(() =>
  typeof route?.query.releaseId === 'string' ? route.query.releaseId : '',
)
const events = computed(() =>
  releaseId.value
    ? (state.data.value?.events ?? []).filter((item) => item.releaseId === releaseId.value)
    : (state.data.value?.events ?? []),
)
const owner = computed(() => workspace.space?.role === 'OWNER' && workspace.canWrite)
function load() {
  const id = workspace.space?.id
  if (!id || mutation.pending.value) return
  return state.load(async () => {
    const [events, inbox] = await Promise.all([
      api.get<ReleaseEvent[]>(`/events?spaceId=${id}`),
      api.get<EventReceipt[]>(`/events/inbox?spaceId=${id}`),
    ])
    return { events, inbox }
  })
}
watch(
  () => workspace.space?.id,
  () => {
    state.reset()
    Object.keys(reasons).forEach((key) => delete reasons[Number(key)])
    void load()
  },
  { immediate: true },
)
async function replay(item: ReleaseEvent) {
  if (!owner.value || item.status !== 'DEAD' || !reasons[item.id]?.trim()) return
  await mutation.run(async ({ isCurrent }) => {
    const result = await api.post<ReleaseEvent>(`/events/${item.id}/replay`, {
      reason: reasons[item.id].trim(),
    })
    if (!isCurrent() || !state.data.value) return
    if (result.id !== item.id) throw new Error('重放返回了不同事件标识，请刷新事件记录。')
    state.data.value = {
      ...state.data.value,
      events: state.data.value.events.map((row) => (row.id === item.id ? result : row)),
    }
  })
}
</script>
<template>
  <div class="p4-page stack">
    <div class="page-heading">
      <div>
        <h1>事件与收件箱</h1>
        <p class="muted">跟踪发布通知的投递、重试与本地接收证据。</p>
      </div>
      <el-button :disabled="mutation.pending.value" @click="load">刷新事件</el-button>
    </div>
    <p class="notice">
      事件记录当时的版本状态；版本之后可能已弃用或损坏。收到 RELEASED
      历史通知不代表版本当前可消费。本地收件箱证据不代表外部智能体平台已上架。
    </p>
    <p v-if="mutation.error.value" class="error-text" role="alert">{{ mutation.error.value }}</p>
    <AsyncState :loading="state.loading.value" :error="state.error.value" @retry="load">
      <template v-if="state.data.value">
        <section class="panel stack">
          <h2>投递队列</h2>
          <p v-if="!events.length" class="muted">暂无发布事件。</p>
          <article v-for="item in events" :key="item.id" class="record stack">
            <div class="row spread">
              <h3>#{{ item.id }} · {{ item.type }}</h3>
              <el-tag
                :type="
                  item.status === 'DEAD' ? 'danger' : item.status === 'SENT' ? 'success' : 'warning'
                "
              >
                {{ item.status }}
              </el-tag>
            </div>
            <dl class="details-grid">
              <dt>事件绑定状态</dt>
              <dd>{{ item.boundStatus }}</dd>
              <dt>版本当前状态</dt>
              <dd>{{ item.releaseStatus }}</dd>
              <dt>版本标识</dt>
              <dd>{{ item.releaseId }}</dd>
              <dt>重试次数</dt>
              <dd>{{ item.retryCount }}</dd>
              <dt>下次重试</dt>
              <dd>{{ item.nextRetryAt ?? '未安排' }}</dd>
              <dt>最近错误</dt>
              <dd>{{ item.lastError ?? '无' }}</dd>
            </dl>
            <template v-if="owner && item.status === 'DEAD'">
              <label>
                人工重放理由
                <textarea
                  v-model="reasons[item.id]"
                  :data-testid="`replay-reason-${item.id}`"
                  rows="2"
                  :disabled="mutation.pending.value"
                />
              </label>
              <div>
                <el-button
                  :disabled="mutation.pending.value || !reasons[item.id]?.trim()"
                  @click="replay(item)"
                >
                  重放此事件
                </el-button>
              </div>
              <p class="muted">保留 eventId {{ item.id }} 及重试历史，接收方按此 id 去重。</p>
            </template>
          </article>
        </section>
        <section class="panel stack">
          <h2>本地收件箱</h2>
          <p v-if="!state.data.value.inbox.length" class="muted">
            尚无接收确认。日志输出不会作为已投递证据。
          </p>
          <article v-for="item in state.data.value.inbox" :key="item.eventId" class="record">
            <b>eventId {{ item.eventId }} · {{ item.type }}</b>
            <p class="muted">{{ item.receivedAt }} · {{ item.releaseId }}</p>
            <details>
              <summary>接收凭据</summary>
              <pre>{{ JSON.stringify(item.receipt, null, 2) }}</pre>
            </details>
          </article>
        </section>
      </template>
    </AsyncState>
  </div>
</template>
