<script setup lang="ts">
import { computed } from 'vue'
import type { OverviewState } from './useOverviewCards'
import { releaseLabels } from './overview'
const props = defineProps<{ state?: OverviewState }>()
defineEmits<{ retry: [] }>()
const failures = computed(() => {
  const value = props.state?.value
  return value
    ? Object.entries({
        关系: value.relations,
        规则: value.rules,
        模型校验: value.conflicts,
        映射校验: value.mappings,
        正式版本: value.release,
      }).filter(([, reading]) => reading.error)
    : []
})
</script>
<template>
  <div class="overview-metrics" aria-live="polite">
    <p v-if="!state || state.loading" class="muted">正在读取关系、规则、正式版本与校验问题…</p>
    <template v-else-if="state.value">
      <dl class="metric-grid">
        <div>
          <dt>关系</dt>
          <dd>{{ state.value.relations.value ?? '未知' }}</dd>
        </div>
        <div>
          <dt>规则</dt>
          <dd>{{ state.value.rules.value ?? '未知' }}</dd>
        </div>
        <div class="wide">
          <dt>当前正式版本</dt>
          <dd>
            {{
              state.value.release.error
                ? '读取失败'
                : state.value.release.value
                  ? `v${state.value.release.value.versionNo}`
                  : '尚未发布'
            }}
          </dd>
        </div>
        <div class="wide">
          <dt>发布状态</dt>
          <dd>
            {{
              state.value.release.error
                ? '未知，请重试'
                : state.value.release.value
                  ? releaseLabels[state.value.release.value.status]
                  : '尚未发布'
            }}
          </dd>
        </div>
        <div class="wide">
          <dt>校验问题</dt>
          <dd v-if="state.value.conflicts.value">
            {{ state.value.conflicts.value.errors.length }} 项错误 ·
            {{ state.value.conflicts.value.warnings.length }} 项警告
          </dd>
          <dd v-else>未知，请重试</dd>
        </div>
        <div class="wide">
          <dt>映射校验</dt>
          <dd v-if="state.value.mappings.value">
            {{
              state.value.mappings.value.checks.length
                ? `${state.value.mappings.value.checks.flatMap((check) => check.issues).length} 项问题 · ${state.value.mappings.value.valid ? '检查完成' : '未通过'}`
                : '未检查'
            }}
          </dd>
          <dd v-else>未知，请重试</dd>
        </div>
      </dl>
      <p v-for="[name, reading] in failures" :key="name" class="error-text">
        {{ name }}：{{ reading.error }}
      </p>
    </template>
    <p v-if="state?.error" class="error-text" role="alert">概览读取失败：{{ state.error }}</p>
    <el-button
      v-if="state && !state.loading && (state.error || failures.length)"
      link
      type="primary"
      @click="$emit('retry')"
    >
      重试概览
    </el-button>
  </div>
</template>
<style scoped>
.metric-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin: 12px 0;
}
.metric-grid > div {
  min-width: 0;
}
.metric-grid .wide {
  grid-column: 1 / -1;
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
dt {
  color: var(--muted);
  font-size: 12px;
}
dd {
  margin: 4px 0 0;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.wide dd {
  margin: 0;
  text-align: right;
  font-size: 13px;
}
.error-text {
  font-size: 12px;
  overflow-wrap: anywhere;
}
</style>
