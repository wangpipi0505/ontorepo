<script setup lang="ts">
/** 来源证据只渲染服务端定位信息，不补造页码、摘录或概率。 */
defineProps<{ location: Record<string, unknown> }>()
function display(value: unknown) {
  return typeof value === 'string' ? value : JSON.stringify(value)
}
</script>
<template>
  <p v-if="location.evidenceScope === 'METADATA_ONLY'" class="readonly-note">
    本候选仅使用元数据证据，未读取实例样例。{{ location.sampleOmissionReason }}
  </p>
  <dl class="details-grid">
    <template v-for="(value, key) in location" :key="key">
      <dt>{{ key }}</dt>
      <dd>{{ display(value) }}</dd>
    </template>
  </dl>
</template>
