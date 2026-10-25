<script setup lang="ts">
/**
 * UIF-O3：提案元素级评论侧栏。
 * 按候选加载评论列表 + 新增评论表单（可选定位到变更行 subject_kind/key）。
 * 数据源：R12 的 proposal_comment 表（ProposalCommentService 暴露端点后接线）。
 */
import { ref, watch } from 'vue'
import { useSession } from '../../stores/session'
import { useMutation } from '../../composables/useMutation'

const props = defineProps<{ candidateId: string }>()
const api = useSession().api
const rows = ref<any[]>([])
const error = ref('')
const busy = ref(false)
const body = ref('')
const subjectKey = ref('')

async function refresh() {
  if (!props.candidateId) return
  busy.value = true
  try {
    rows.value = await api.get(`/releases/proposal-comments?candidateId=${props.candidateId}`)
    error.value = ''
  } catch (e: any) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
watch(() => props.candidateId, refresh, { immediate: true })

async function submit(subjectKind?: string, subjectKey?: string) {
  if (!body.value.trim()) return
  busy.value = true
  try {
    await api.post('/releases/proposal-comments', {
      candidateId: props.candidateId,
      subjectKind: subjectKind ?? null,
      subjectKey: subjectKey ?? null,
      body: body.value.trim(),
    })
    body.value = ''
    subjectKey.value = ''
    await refresh()
  } catch (e: any) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <div class="stack">
    <h3>评审评论</h3>
    <div v-for="row in rows" :key="row.id" class="comment" :class="{ inline: row.subjectKey }">
      <b>{{ row.actorName }}</b>
      <span class="muted">{{ row.createdAt }}</span>
      <span v-if="row.subjectKey" class="pill">{{ row.subjectKind }}/{{ row.subjectKey }}</span>
      <p>{{ row.body }}</p>
    </div>
    <div class="form">
      <textarea v-model="body" rows="2" placeholder="填写评审评论…" />
      <button :disabled="!body.trim() || busy" @click="submit()">提交</button>
    </div>
  </div>
</template>
<style scoped>
.comment { border-bottom: 1px solid #e5e7eb; padding: 6px 0; }
.comment.inline { padding-left: 12px; border-left: 2px solid #93c5fd; }
.pill { background: #dbeafe; border-radius: 4px; padding: 0 6px; font-size: 11px; }
.form textarea { width: 100%; border: 1px solid #d1d5db; border-radius: 4px; padding: 6px; }
.form button { margin-top: 4px; }
</style>
