<script setup lang="ts">
/**
 * UIF-O4：门禁报告三段式视图（形状库分组 + 违规列表 + 跳转修复）。
 *
 * 数据来自 CONFLICT 门禁的 report（{conflicts:{errors,warnings},shacl:{conforms,report}}），
 * 不改后端契约：规则冲突取 errors/warnings（实体级，entityIds 支持跳转建模页），
 * SHACL 报告以 Turtle 全文展示（Jena 报告模型无逐条结构化输出，不伪造解析）。
 */
import { computed } from 'vue'
import type { PublicationCheck } from '@onto/api-client'
const props = defineProps<{ check: PublicationCheck }>()
const emit = defineEmits<{ 'fix-entity': [entityId: string] }>()

const conflicts = computed<Record<string, unknown> | null>(() => {
  const report = props.check?.report as Record<string, unknown> | undefined
  return (report?.conflicts as Record<string, unknown>) ?? null
})
const errors = computed<Record<string, unknown>[]>(() =>
  Array.isArray(conflicts.value?.errors) ? (conflicts.value!.errors as Record<string, unknown>[]) : [],
)
const warnings = computed<Record<string, unknown>[]>(() =>
  Array.isArray(conflicts.value?.warnings) ? (conflicts.value!.warnings as Record<string, unknown>[]) : [],
)
const shacl = computed<Record<string, unknown> | null>(() => {
  const report = props.check?.report as Record<string, unknown> | undefined
  return (report?.shacl as Record<string, unknown>) ?? null
})
const shaclTurtle = computed(() => String(shacl.value?.report ?? ''))
const entityIds = (item: Record<string, unknown>) =>
  Array.isArray(item.entityIds) ? (item.entityIds as string[]) : []
</script>

<template>
  <section class="stack three-pane" aria-label="门禁报告三段式">
    <!-- 段一：分组摘要（形状库语义——按结论/类别聚合的条目组） -->
    <div class="groups">
      <h4>问题分组</h4>
      <div class="group" data-group="errors">
        <b :class="errors.length ? 'bad' : 'ok'">冲突 {{ errors.length }}</b>
      </div>
      <div class="group" data-group="warnings">
        <b :class="warnings.length ? 'warn' : 'ok'">警告 {{ warnings.length }}</b>
      </div>
      <div class="group" data-group="shacl">
        <b :class="shacl?.conforms === false ? 'bad' : 'ok'">
          SHACL {{ shacl?.conforms === false ? '不符' : shacl ? '符合' : '未运行' }}
        </b>
      </div>
      <p class="muted">点击分组在右侧定位对应明细；实体标识可跳建模页。</p>
    </div>
    <!-- 段二：明细列表 -->
    <div class="details">
      <h4>明细</h4>
      <template v-if="errors.length || warnings.length">
        <div v-for="(item, i) in errors" :key="'e' + i" class="row violation error-row">
          <span class="dot critical" role="img" aria-label="错误" />
          <b>{{ item.kind }}</b>
          <span>{{ item.message }}</span>
          <el-button
            v-for="id in entityIds(item)"
            :key="id"
            link
            type="primary"
            @click="emit('fix-entity', id)"
            >修复 {{ String(id).slice(0, 8) }}…</el-button
          >
        </div>
        <div v-for="(item, i) in warnings" :key="'w' + i" class="row violation">
          <span class="dot warning" role="img" aria-label="警告" />
          <b>{{ item.kind }}</b>
          <span>{{ item.message }}</span>
          <el-button
            v-for="id in entityIds(item)"
            :key="id"
            link
            type="primary"
            @click="emit('fix-entity', id)"
            >查看 {{ String(id).slice(0, 8) }}…</el-button
          >
        </div>
      </template>
      <p v-else-if="shacl?.conforms === false" class="muted">
        规则冲突为零；SHACL 数据校验不符，全文见下方报告。
      </p>
      <p v-else class="muted">此门禁没有可显示的结构化明细。</p>
    </div>
    <!-- 段三：SHACL 报告全文 -->
    <div class="shacl">
      <h4>SHACL 报告（Turtle）</h4>
      <pre v-if="shaclTurtle">{{ shaclTurtle }}</pre>
      <p v-else class="muted">未生成 SHACL 报告。</p>
    </div>
  </section>
</template>

<style scoped>
.three-pane {
  display: grid;
  gap: 12px;
}
.group {
  margin: 4px 0;
}
.group .bad {
  color: var(--st-fail-text);
}
.group .warn {
  color: var(--st-suspend-text);
}
.group .ok {
  color: var(--st-ok-text);
}
.violation {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin: 6px 0;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  display: inline-block;
}
.dot.critical {
  background: var(--st-fail);
}
.dot.warning {
  background: var(--st-suspend);
}
.error-row {
  background: color-mix(in srgb, var(--st-fail) 6%, white);
  border-radius: 4px;
  padding: 4px 6px;
}
.shacl pre {
  max-height: 240px;
  overflow: auto;
  font-size: 12px;
  background: var(--el-fill-color-light, #f5f7fa);
  padding: 8px;
  border-radius: 4px;
}
</style>
