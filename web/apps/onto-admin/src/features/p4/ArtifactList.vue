<script setup lang="ts">
import type { ReleaseArtifact } from '../../../../../packages/api-client/src/release'
defineProps<{ artifacts: ReleaseArtifact[] }>()
const labels = {
  BUNDLE: '冻结内容包',
  MANIFEST: '操作清单',
  CONTEXT_PACK: '上下文包',
  KNOWLEDGE_INDEX: '知识索引',
}
</script>
<template>
  <section class="stack">
    <h3>发布制品</h3>
    <p v-if="!artifacts.length" class="muted">尚无制品证据。准备成功后由服务端返回就绪状态。</p>
    <article v-for="item in artifacts" :key="item.kind" class="record">
      <div class="row spread">
        <b>{{ labels[item.kind] }}</b>
        <el-tag :type="item.ready ? 'success' : 'warning'">
          {{ item.ready ? '已就绪' : '未就绪' }}
        </el-tag>
      </div>
      <p class="identity">{{ item.sha256 ?? '摘要尚未生成' }}</p>
      <span class="muted">{{ item.size === null ? '大小未知' : `${item.size} bytes` }}</span>
    </article>
  </section>
</template>
