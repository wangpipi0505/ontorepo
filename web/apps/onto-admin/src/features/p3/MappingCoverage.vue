<script setup lang="ts">
/** 覆盖率只用服务端非负计数和明确分母；零分母显示无可统计项。 */
import { computed } from 'vue'
import type { MappingValidation } from '@onto/api-client'
const props = defineProps<{ value: MappingValidation }>()
const total = computed(
  () =>
    props.value.coverage.mapped + props.value.coverage.pending + props.value.coverage.unmappable,
)
const labels = {
  CLASS_OBJECT: '类与对象映射',
  REQUIRED_PROPERTY: '必填属性',
  RELATION_KEYS: '关系连接键',
  REACHABLE_REFERENCE: '可达引用',
}
</script>
<template>
  <section class="panel stack">
    <h2>完整性与覆盖度</h2>
    <p>
      {{ value.valid ? '完整性校验通过' : '存在待修复问题' }} ·
      {{ total ? `已映射 ${value.coverage.mapped} / 分母 ${total}` : '无可统计项' }}
    </p>
    <p class="muted">
      分母依据：{{ value.coverageBasis }}
      <br />
      待映射 {{ value.coverage.pending }} · 不可映射 {{ value.coverage.unmappable }}
    </p>
    <div v-for="check in value.checks" :key="check.kind">
      <h3>{{ labels[check.kind] }} · {{ check.passed ? '通过' : '未通过' }}</h3>
      <p v-for="(issue, index) in check.issues" :key="index" class="error-text">
        {{ issue.code }}：{{ issue.message }}
        <code>{{ issue.entityId }} {{ issue.field }}</code>
      </p>
    </div>
    <p>
      受影响操作：{{
        value.affectedOperations.map((item) => `${item.name} (${item.code})`).join('、') || '无'
      }}
    </p>
  </section>
</template>
