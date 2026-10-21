<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSession } from '../stores/session'
const session = useSession(),
  router = useRouter(),
  error = ref(''),
  pending = ref(false)
async function refresh() {
  pending.value = true
  error.value = ''
  try {
    await session.refreshPermissions()
    if (session.has('space.read')) await router.replace('/spaces')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '权限读取失败。'
  } finally {
    pending.value = false
  }
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>当前账号</h1>
      <p class="muted">{{ session.user?.displayName }} · {{ session.user?.username }}</p>
    </div>
  </div>
  <section class="panel stack">
    <h2>功能访问范围</h2>
    <p>
      当前页面不可访问时，可在导航中选择已授权功能，或联系管理员调整角色。空间身份和数据范围仍需单独授权。
    </p>
    <p v-if="!session.permissions?.length" class="readonly-note">当前未取得可用功能权限。</p>
    <p v-if="error" class="error-text" role="alert">{{ error }}</p>
    <el-button :loading="pending" @click="refresh">重新读取权限</el-button>
  </section>
</template>
