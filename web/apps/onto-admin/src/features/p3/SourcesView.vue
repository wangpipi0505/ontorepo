<script setup lang="ts">
/** 数据源目录负责权限与请求编排；配置、上传、探测和不可变元数据各有独立组件。 */
import { computed, onMounted, ref, shallowRef, watch } from 'vue'
import type {
  Datasource,
  ManagedFile,
  MetadataDiff,
  MetadataSnapshot,
  MetadataSummary,
  SourceCapability,
  SourceProbe,
  SourceSync,
  SourceWrite,
  Space,
} from '@onto/api-client'
import { useDraftDocument } from '../p2/useDraftDocument'
import { useSession } from '../../stores/session'
import { useResource } from '../../composables/useResource'
import { useMutation, confirmAction } from '../../composables/useMutation'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import SourceForm from './SourceForm.vue'
import ManagedUpload from './ManagedUpload.vue'
import MetadataPanel from './MetadataPanel.vue'
import { sourceLabels } from './sourceFields'
const session = useSession(),
  api = session.api
const editor = useDraftDocument(async (id) => {
  const [sources, files, capabilities] = await Promise.all([
    api.get<Datasource[]>(`/datasources?spaceId=${id}`),
    api.get<ManagedFile[]>(`/data-files?spaceId=${id}`),
    api.get<SourceCapability[]>(`/datasources/capabilities?spaceId=${id}`),
  ])
  return { sources, files, capabilities }
})
const selected = ref(''),
  snapshotId = ref(''),
  open = ref(false),
  editing = shallowRef<Datasource | null>(null)
const snapshots = useResource<MetadataSummary[]>([]),
  metadata = useResource<MetadataSnapshot | null>(null),
  probe = useResource<SourceProbe | null>(null)
const diff = shallowRef<MetadataDiff | null>(null),
  probeMutation = useMutation()
const data = computed(() => editor.resource.data.value?.items)
const owner = computed(() => editor.workspace.space?.role === 'OWNER' && editor.workspace.canWrite)
const busy = computed(() => editor.mutation.pending.value || probeMutation.pending.value)
const source = computed(() => data.value?.sources.find((item) => item.id === selected.value))
let applyingSyncedSnapshot = false
async function load() {
  if (open.value || busy.value) return
  await editor.load()
}
async function loadSnapshots() {
  const id = selected.value,
    spaceId = editor.workspace.space?.id
  if (!id || !spaceId) return
  await snapshots.load(() =>
    editor.api.get<MetadataSummary[]>(`/datasources/${id}/snapshots?spaceId=${spaceId}`),
  )
}
async function loadSnapshot() {
  const id = selected.value,
    key = snapshotId.value,
    spaceId = editor.workspace.space?.id
  if (!id || !key || !spaceId) return
  await metadata.load(() =>
    editor.api.get<MetadataSnapshot>(`/datasources/${id}/snapshots/${key}?spaceId=${spaceId}`),
  )
}
watch(selected, () => {
  snapshots.reset()
  metadata.reset()
  probe.reset()
  diff.value = null
  snapshotId.value = ''
  void loadSnapshots()
})
watch(
  snapshotId,
  () => {
    metadata.reset()
    diff.value = null
    if (snapshotId.value && !applyingSyncedSnapshot) void loadSnapshot()
  },
  { flush: 'sync' },
)
function edit(item: Datasource | null) {
  if (!owner.value || !editor.resource.data.value) return
  editing.value = item
  open.value = true
  editor.mutation.error.value = ''
}
async function save(value: SourceWrite) {
  const spaceId = editor.workspace.space?.id,
    id = editing.value?.id
  if (!spaceId || !owner.value) return
  if (
    await editor.save((expectedDraftRev) =>
      id
        ? editor.api.put<Space>(`/datasources/${id}`, {
            ...value,
            ownerId: editing.value?.ownerId ?? session.user?.id,
            spaceId,
            expectedDraftRev,
          })
        : editor.api.post<Space>('/datasources', {
            ...value,
            ownerId: session.user?.id,
            spaceId,
            expectedDraftRev,
          }),
    )
  ) {
    open.value = false
    await editor.load()
  }
}
async function remove(item: Datasource) {
  const context = editor.workspace.captureContext(),
    spaceId = editor.workspace.space?.id,
    revision = editor.resource.data.value?.space.draftRev
  if (!owner.value || !spaceId || revision === undefined) return
  if (!(await confirmAction(`删除“${item.name}”？被映射引用时服务端会拒绝并给出影响对象。`))) return
  if (!editor.workspace.isCurrentContext(context)) return
  if (
    await editor.save(() =>
      editor.api.delete<Space>(
        `/datasources/${item.id}?spaceId=${spaceId}&expectedDraftRev=${revision}`,
      ),
    )
  ) {
    if (selected.value === item.id) selected.value = ''
    await editor.load()
  }
}
async function test() {
  const id = selected.value,
    spaceId = editor.workspace.space?.id
  if (!id || !spaceId || !owner.value) return
  await probeMutation.run(async ({ isCurrent }) => {
    // 源选择在探测期间锁定；useResource 在切源/卸载时同时撤销迟到结果。
    const result = await probe.load(() =>
      editor.api.post<SourceProbe>(`/datasources/${id}/probe`, { spaceId }),
    )
    if (!isCurrent() || !result) return
  })
}
async function sync() {
  const id = selected.value,
    spaceId = editor.workspace.space?.id
  if (!id || !spaceId || !owner.value) return
  // 同步成为新读取代次：历史快照的迟到成功和错误均失去覆盖资格。
  metadata.reset()
  diff.value = null
  let result: SourceSync | undefined
  if (
    await editor.save(async (expectedDraftRev) => {
      result = await editor.api.post<SourceSync>(`/datasources/${id}/sync`, {
        spaceId,
        expectedDraftRev,
      })
      return result.space
    })
  ) {
    if (result) {
      metadata.reset()
      // 同步响应已携带完整不可变快照，同步选择器时不再发起重复 GET。
      applyingSyncedSnapshot = true
      snapshotId.value = result.snapshot.id
      applyingSyncedSnapshot = false
      diff.value = result.diff
      metadata.data.value = result.snapshot
    }
    await editor.load()
    await loadSnapshots()
  }
}
async function upload(file: File) {
  const spaceId = editor.workspace.space?.id
  if (!spaceId || !owner.value || open.value) return
  if (
    await editor.save(async (expectedDraftRev) => {
      const body = new FormData()
      body.append('file', file)
      return (
        await editor.api.post<{ space: Space; file: ManagedFile }>(
          `/data-files?spaceId=${spaceId}&expectedDraftRev=${expectedDraftRev}`,
          body,
        )
      ).space
    })
  )
    await editor.load()
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>数据源管理</h1>
      <p class="muted">空间内安全配置、连接探测、元数据快照与受管文件。</p>
    </div>
    <div class="row">
      <el-button :disabled="open || busy" @click="load">刷新目录</el-button>
      <el-button v-if="owner" type="primary" :disabled="!data || busy" @click="edit(null)">
        新增数据源
      </el-button>
    </div>
  </div>
  <div class="stack">
    <p v-if="editor.mutation.error.value" class="error-text" role="alert">
      {{ editor.mutation.error.value }}
    </p>
    <AsyncState
      :loading="editor.resource.loading.value"
      :error="editor.resource.error.value"
      @retry="load"
    >
      <template v-if="data">
        <section class="panel">
          <el-table :data="data.sources" empty-text="尚无数据源">
            <el-table-column prop="name" label="名称" />
            <el-table-column prop="code" label="编码" />
            <el-table-column label="类别">
              <template #default="{ row }">
                {{ sourceLabels[row.kind as keyof typeof sourceLabels] }}
              </template>
            </el-table-column>
            <el-table-column prop="dialect" label="协议" />
            <el-table-column label="说明" min-width="160">
              <template #default="{ row }">{{ row.description || '—' }}</template>
            </el-table-column>
            <el-table-column label="租户" width="110">
              <template #default="{ row }">{{ row.tenant || '—' }}</template>
            </el-table-column>
            <el-table-column prop="status" label="状态" />
            <el-table-column prop="lastTestAt" label="上次探测" />
            <el-table-column label="操作" min-width="200">
              <template #default="{ row }">
                <el-button text :disabled="busy || open" @click="selected = row.id">
                  元数据
                </el-button>
                <el-button v-if="owner" text :disabled="busy || open" @click="edit(row)">
                  编辑
                </el-button>
                <el-button
                  v-if="owner"
                  text
                  type="danger"
                  :disabled="busy || open"
                  @click="remove(row)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
        <section v-if="open" class="panel">
          <h2>{{ editing ? '编辑数据源' : '新增数据源' }}</h2>
          <SourceForm
            :value="editing"
            :files="data.files"
            :can-write="owner"
            :pending="busy"
            :stale="editor.draft.stale.value"
            :error="editor.mutation.error.value"
            @save="save"
            @cancel="open = false"
          />
        </section>
        <section v-if="source" class="panel stack">
          <h2>{{ source.name }} · 元数据</h2>
          <p class="muted">访问范围：{{ source.accessScope.join('、') }}</p>
          <div class="row">
            <el-button v-if="owner" :disabled="busy || open" @click="test">
              连接三阶段测试
            </el-button>
            <el-button v-if="owner" :disabled="busy || open" @click="sync">同步元数据</el-button>
          </div>
          <AsyncState
            :loading="snapshots.loading.value"
            :error="snapshots.error.value"
            @retry="loadSnapshots"
          >
            <el-form label-position="top">
              <el-form-item label="不可变元数据快照">
                <el-select v-model="snapshotId" :disabled="busy || open" placeholder="选择历史快照">
                  <el-option
                    v-for="item in snapshots.data.value"
                    :key="item.id"
                    :value="item.id"
                    :label="`${item.capturedAt} · ${item.sha256}`"
                  />
                </el-select>
              </el-form-item>
            </el-form>
            <p v-if="!snapshots.data.value.length" class="muted">尚无快照，请 OWNER 同步元数据。</p>
          </AsyncState>
        </section>
        <AsyncState :loading="probe.loading.value" :error="probe.error.value" @retry="test">
          <MetadataPanel :snapshot="null" :diff="null" :probe="probe.data.value" />
        </AsyncState>
        <AsyncState
          :loading="metadata.loading.value"
          :error="metadata.error.value"
          @retry="loadSnapshot"
        >
          <MetadataPanel :snapshot="metadata.data.value" :diff="diff" :probe="null" />
        </AsyncState>
        <ManagedUpload
          :can-write="owner && !open"
          :pending="busy"
          :stale="editor.draft.stale.value"
          :files="data.files"
          @upload="upload"
        />
        <section class="panel">
          <h2>协议能力与验收状态</h2>
          <p class="muted">
            “已实现”表示代码能力；本地验收证据与当前源连通性分别展示，不能据此认定外部实例已连接。
          </p>
          <div
            v-for="item in data.capabilities"
            :key="`${item.kind}-${item.dialect}`"
            class="stack capability"
          >
            <h3>{{ sourceLabels[item.kind] }} / {{ item.dialect }}</h3>
            <div class="row">
              <span class="pill">{{ item.implemented ? '已实现' : '未实现' }}</span>
              <span class="pill">
                {{ item.verifiedLocally ? '本地真实实例已验收' : '真实实例待外部验收' }}
              </span>
            </div>
            <p>{{ item.verificationNote }}</p>
            <p class="muted">
              查询：{{ item.capabilities.join('、') }}
              <br />
              限制：{{ item.limits.join('；') }}
            </p>
          </div>
        </section>
      </template>
    </AsyncState>
  </div>
</template>
<style scoped>
.capability {
  padding: 18px 0;
  border-top: 1px solid var(--line);
  gap: 8px;
}
.capability p,
.capability h3 {
  margin-bottom: 0;
}
</style>
