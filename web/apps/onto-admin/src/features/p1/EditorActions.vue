<script setup lang="ts">
/** 不渲染 form；由业务表单作为唯一原生提交边界。 */
defineProps<{ pending: boolean; error: string; stale: boolean; canWrite: boolean }>()
defineEmits<{ cancel: [] }>()
</script>
<template>
  <p v-if="stale" class="error-text" role="alert">
    表单已过期。请复制保留输入，关闭后刷新本页，再重新编辑。
  </p>
  <p v-if="error" class="error-text" role="alert">{{ error }}</p>
  <div class="row">
    <el-button
      type="primary"
      native-type="submit"
      :loading="pending"
      :disabled="!canWrite || stale"
    >
      保存
    </el-button>
    <el-button :disabled="pending" @click="$emit('cancel')">取消</el-button>
  </div>
</template>
