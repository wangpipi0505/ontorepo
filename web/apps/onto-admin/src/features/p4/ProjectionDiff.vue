<script setup lang="ts">
/** UIF-O1：版本对比可视化——在既有投影 diff 上叠加变更行着色（R10 severity）与
 *  影响摘要卡；字段级 before/after 双栏，破坏性行红框、收紧行琥珀框。 */
import { computed, ref } from 'vue'
import type { ReleaseDiff, ReleaseDiffItem } from '../../../../../packages/api-client/src/release'
const props = defineProps<{ diff: ReleaseDiff }>()

type Change = ReleaseDiffItem & { severity?: string; reason?: string }
type Level = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'SAFE'

/** 前端兜底分级：后端未回 severity 时按同一口径推导（删除>收紧>变更>安全）。 */
function deriveSeverity(item: Change): Level {
  if (item.severity) return item.severity as Level
  if (item.before !== undefined && item.after === undefined) return 'CRITICAL'
  if (item.before !== undefined && item.after !== undefined) {
    if (Object.keys(item.before ?? {}).length > Object.keys(item.after ?? {}).length) return 'HIGH'
    return 'MEDIUM'
  }
  return 'SAFE'
}

const rows = computed(() => {
  const result: (Change & { section: string })[] = []
  for (const item of props.diff.added) result.push({ ...item, section: 'added' })
  for (const item of props.diff.removed) result.push({ ...item, section: 'removed' })
  for (const item of props.diff.changed) result.push({ ...item, section: 'changed' })
  return result.sort((a, b) => rank(a) - rank(b))
})
function rank(item: Change): number {
  const severity = deriveSeverity(item)
  return severity === 'CRITICAL' ? 0 : severity === 'HIGH' ? 1 : severity === 'MEDIUM' ? 2 : 3
}
const severityLabel: Record<Level, { text: string; cls: string }> = {
  CRITICAL: { text: '破坏性', cls: 'sev-critical' },
  HIGH: { text: '高影响', cls: 'sev-high' },
  MEDIUM: { text: '需回归', cls: 'sev-medium' },
  SAFE: { text: '安全', cls: 'sev-safe' },
}
const sectionLabel: Record<string, string> = { added: '新增', removed: '删除', changed: '修改' }
const summary = computed(() => {
  const counts: Record<Level, number> = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, SAFE: 0 }
  for (const row of rows.value) counts[deriveSeverity(row)]++
  return counts
})
const expanded = ref<string | null>(null)
function toggle(key: string) {
  expanded.value = expanded.value === key ? null : key
}
function fields(before: unknown, after: unknown) {
  const b = (before ?? {}) as Record<string, unknown>
  const a = (after ?? {}) as Record<string, unknown>
  const keys = new Set([...Object.keys(b), ...Object.keys(a)])
  return [...keys]
    .filter((k) => JSON.stringify(b[k]) !== JSON.stringify(a[k]))
    .map((k) => ({ key: k, before: b[k], after: a[k] }))
}
</script>

<template>
  <div class="stack">
    <!-- 影响摘要卡：四级计数 -->
    <div class="impact" aria-label="变更影响摘要">
      <span class="chip sev-critical">破坏性 {{ summary.CRITICAL }}</span>
      <span class="chip sev-high">高影响 {{ summary.HIGH }}</span>
      <span class="chip sev-medium">需回归 {{ summary.MEDIUM }}</span>
      <span class="chip sev-safe">安全 {{ summary.SAFE }}</span>
    </div>
    <!-- 变更行（按影响排序：破坏性优先），点击展开字段级对比 -->
    <div
      v-for="row in rows"
      :key="row.kind + '/' + row.key"
      class="change-row"
      :class="'row-' + deriveSeverity(row).toLowerCase()"
      role="button"
      tabindex="0"
      @click="toggle(row.kind + '/' + row.key)"
      @keydown.enter.prevent="toggle(row.kind + '/' + row.key)"
    >
      <div class="row-head">
        <span class="badge" :class="'badge-' + deriveSeverity(row).toLowerCase()">
          {{ severityLabel[deriveSeverity(row)].text }}
        </span>
        <b>{{ row.kind }}</b>
        <code class="key">{{ row.key }}</code>
        <span class="muted change-type">{{ sectionLabel[row.section] }}</span>
      </div>
      <div v-if="expanded === row.kind + '/' + row.key" class="row-body">
        <p v-if="row.reason" class="muted">影响：{{ row.reason }}</p>
        <div v-for="f in fields(row.before, row.after)" :key="f.key" class="field">
          <code>{{ f.key }}</code>
          <span class="before">{{ JSON.stringify(f.before ?? '（无）') }}</span>
          <span class="arrow">→</span>
          <span class="after">{{ JSON.stringify(f.after ?? '（无）') }}</span>
        </div>
        <details v-if="row.before || row.after">
          <summary>完整内容</summary>
          <div class="form-columns">
            <div v-if="row.before !== undefined">
              <h4>原内容</h4>
              <pre>{{ JSON.stringify(row.before, null, 2) }}</pre>
            </div>
            <div v-if="row.after !== undefined">
              <h4>新内容</h4>
              <pre>{{ JSON.stringify(row.after, null, 2) }}</pre>
            </div>
          </div>
        </details>
      </div>
    </div>
    <p v-if="!rows.length" class="muted">两个版本投影一致，无差异。</p>
  </div>
</template>

<style scoped>
.impact {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.chip {
  border-radius: 999px;
  padding: 2px 10px;
  font-size: 12px;
  border: 1px solid var(--line, #e5e7eb);
}
.chip.sev-critical {
  background: color-mix(in srgb, var(--st-fail, #dc2626) 10%, white);
  color: var(--st-fail-text, #991b1b);
}
.chip.sev-high {
  background: color-mix(in srgb, var(--st-suspend, #d97706) 10%, white);
  color: var(--st-suspend-text, #92400e);
}
.chip.sev-medium {
  background: color-mix(in srgb, var(--st-wait, #9ca3af) 10%, white);
  color: var(--st-wait-text, #4b5563);
}
.chip.sev-safe {
  background: color-mix(in srgb, var(--st-ok, #16a34a) 10%, white);
  color: var(--st-ok-text, #166534);
}
.change-row {
  border: 1px solid var(--line, #e5e7eb);
  border-radius: 6px;
  margin: 6px 0;
  padding: 6px 10px;
  cursor: pointer;
}
.change-row:hover {
  border-color: var(--brand-primary, #2563eb);
}
.change-row.row-critical {
  border-color: color-mix(in srgb, var(--st-fail, #dc2626) 35%, white);
}
.change-row.row-high {
  border-color: color-mix(in srgb, var(--st-suspend, #d97706) 35%, white);
}
.row-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.badge {
  border-radius: 4px;
  padding: 1px 6px;
  font-size: 11px;
}
.badge-critical {
  background: color-mix(in srgb, var(--st-fail, #dc2626) 12%, white);
  color: var(--st-fail-text, #991b1b);
}
.badge-high {
  background: color-mix(in srgb, var(--st-suspend, #d97706) 12%, white);
  color: var(--st-suspend-text, #92400e);
}
.badge-medium {
  background: color-mix(in srgb, var(--st-wait, #9ca3af) 12%, white);
  color: var(--st-wait-text, #4b5563);
}
.badge-safe {
  background: color-mix(in srgb, var(--st-ok, #16a34a) 12%, white);
  color: var(--st-ok-text, #166534);
}
.key {
  overflow-wrap: anywhere;
}
.change-type {
  margin-left: auto;
}
.field {
  display: flex;
  gap: 8px;
  align-items: baseline;
  font-size: 13px;
  margin: 3px 0;
  overflow-wrap: anywhere;
}
.field .before {
  color: var(--st-fail-text, #991b1b);
  text-decoration: line-through;
}
.field .after {
  color: var(--st-ok-text, #166534);
}
.row-body pre {
  max-height: 240px;
  overflow: auto;
}
</style>
