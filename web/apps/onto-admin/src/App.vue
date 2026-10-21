<script setup lang="ts">
import { watch } from 'vue'
import { useRouter } from 'vue-router'
import { useSession } from './stores/session'
import { useWorkspace } from './stores/workspace'
const session = useSession(),
  workspace = useWorkspace(),
  router = useRouter()
// 任意接口的 401 都会撤销内存会话，并清除空间内容，防止旧账号工作区残留。
watch(
  () => session.token,
  (token) => {
    if (!token) {
      workspace.clear()
      void router.replace('/login')
    }
  },
)
</script>
<template><router-view /></template>
