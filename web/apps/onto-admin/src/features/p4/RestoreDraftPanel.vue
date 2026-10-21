<script setup lang="ts">
/** 确认面板捕获打开时修订，409 或外部刷新后不能借新修订覆盖用户草稿。 */
import { computed, ref } from 'vue'
import { ApiError, type Space } from '@onto/api-client'
import type { Release } from '../../../../../packages/api-client/src/release'
import { useWorkspace } from '../../stores/workspace'
import { useSession } from '../../stores/session'
import { useMutation } from '../../composables/useMutation'
import './p4.css'
const props = defineProps<{ release: Release }>()
const emit = defineEmits<{ restored: []; cancel: [] }>()
const workspace = useWorkspace(),
  api = useSession().api,
  mutation = useMutation()
const expectedDraftRev = workspace.space?.draftRev,
  releaseId = props.release.id
const reason = ref(''),
  confirmed = ref(false),
  locked = ref(false),
  done = ref(false)
const allowed = computed(
  () =>
    workspace.space?.role === 'OWNER' &&
    workspace.canWrite &&
    !locked.value &&
    !done.value &&
    props.release.status !== 'CORRUPTED' &&
    expectedDraftRev === workspace.space?.draftRev &&
    releaseId === props.release.id,
)
async function restore() {
  if (!allowed.value || !confirmed.value || !reason.value.trim()) return
  await mutation.run(async ({ isCurrent }) => {
    try {
      const updated = await api.post<Space>(`/releases/${releaseId}/restore-draft`, {
        expectedDraftRev,
        reason: reason.value.trim(),
      })
      if (!isCurrent()) return
      workspace.accept(updated)
      done.value = true
      emit('restored')
    } catch (cause) {
      if (isCurrent() && cause instanceof ApiError && cause.status === 409) locked.value = true
      throw cause
    }
  })
}
</script>
<template>
  <section class="p4-page panel stack">
    <h2>恢复历史版本到草稿</h2>
    <p class="notice">
      目标 v{{ release.versionNo }} · {{ release.id }}。将覆盖打开面板时的草稿 r{{
        expectedDraftRev
      }}，生成新修订；历史版本保持不变，恢复后需要重新冻结和发布。
    </p>
    <p class="identity">
      目标摘要 {{ release.bundleSha256 }}
      <br />
      当前草稿 r{{ workspace.space?.draftRev }}
    </p>
    <label>
      恢复理由
      <textarea
        v-model="reason"
        data-testid="restore-reason"
        rows="3"
        :disabled="mutation.pending.value || done"
      />
    </label>
    <label class="check-label">
      <input
        v-model="confirmed"
        data-testid="restore-confirm"
        type="checkbox"
        :disabled="!allowed || mutation.pending.value"
      />
      确认使用此版本全量覆盖当前草稿内容
    </label>
    <p v-if="!allowed && !done" class="error-text">
      当前修订已变化、版本损坏或没有恢复权限。请保留理由，关闭后刷新版本与草稿再操作。
    </p>
    <p v-if="done" class="notice">草稿已恢复，尚未自动发布。</p>
    <p v-if="mutation.error.value" class="error-text" role="alert">{{ mutation.error.value }}</p>
    <div class="row">
      <el-button
        type="danger"
        :disabled="!allowed || !confirmed || !reason.trim()"
        :loading="mutation.pending.value"
        @click="restore"
      >
        确认覆盖并恢复草稿
      </el-button>
      <el-button :disabled="mutation.pending.value" @click="emit('cancel')">关闭恢复面板</el-button>
    </div>
  </section>
</template>
