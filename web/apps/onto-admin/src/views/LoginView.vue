<script setup lang="ts">
/** 本地账号登录入口；提交加锁避免重复创建会话，失败保留账号并给出可重试的明确提示。 */
import { reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useSession } from '../stores/session'
import { useMutation } from '../composables/useMutation'
const form = reactive({ username: '', password: '' }),
  session = useSession(),
  router = useRouter()
const { pending, error, run } = useMutation()
const expired = session.consumeExpiredNotice()
async function login() {
  if (!form.username.trim() || !form.password) {
    error.value = '请输入账号和密码。'
    return
  }
  await run(async () => {
    await session.login(form.username.trim(), form.password)
    form.password = ''
    await router.replace(session.has('space.read') ? '/spaces' : '/account')
  })
}
</script>
<template>
  <main class="login-page">
    <section class="login-context">
      <div class="login-mark">领域本体</div>
      <h1>
        让领域知识
        <br />
        成为可验证的模型。
      </h1>
      <p>对象与属性 → 模型操作 → 候选冻结 → 试执行</p>
      <small>以对象、属性与操作构建领域模型</small>
    </section>
    <section class="login-form panel">
      <span class="muted">ONTOLOGY WORKBENCH</span>
      <h2>登录工作台</h2>
      <p class="muted">使用管理员为你配置的本地账号。</p>
      <p v-if="expired" class="readonly-note" role="status">
        登录状态已失效，请重新登录后继续操作。
      </p>

      <el-form label-position="top" @submit.prevent="login">
        <el-form-item label="账号" required>
          <el-input
            v-model="form.username"
            maxlength="100"
            autocomplete="username"
            autofocus
            placeholder="请输入账号"
          />
        </el-form-item>
        <el-form-item label="密码" required>
          <el-input
            v-model="form.password"
            maxlength="200"
            type="password"
            show-password
            autocomplete="current-password"
            placeholder="请输入密码"
          />
        </el-form-item>
        <p v-if="error" class="error-text" role="alert">{{ error }}</p>
        <el-button native-type="submit" type="primary" :loading="pending" class="login-submit">
          登录
        </el-button>
      </el-form>

      <p class="muted session-note">会话仅保留在当前页面内存中，刷新后需重新登录。</p>
    </section>
  </main>
</template>
<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  align-items: center;
  gap: 80px;
  max-width: 1100px;
  margin: auto;
  padding: 64px 40px;
}
.login-mark {
  font-size: 16px;
  color: var(--primary);
  margin-bottom: 36px;
  font-weight: 600;
}
.login-context h1 {
  font-size: 44px;
  line-height: 1.45;
  letter-spacing: -1.5px;
  margin-bottom: 24px;
}
.login-context p,
.login-context small {
  color: var(--muted);
}
.login-context small {
  font-size: 12px;
}
.login-form {
  padding: 38px;
}
.login-form h2 {
  font-size: 25px;
  margin: 14px 0 12px;
}
.login-submit {
  width: 100%;
  height: 42px;
}
.session-note {
  margin: 24px 0 0;
  font-size: 11px;
}
@media (max-width: 750px) {
  .login-page {
    display: block;
    padding: 36px 20px;
    max-width: 460px;
  }
  .login-context h1 {
    font-size: 28px;
  }
  .login-context {
    margin-bottom: 30px;
  }
  .login-mark {
    margin-bottom: 18px;
  }
  .login-context p,
  .login-context small {
    display: none;
  }
  .login-form {
    padding: 24px;
  }
}
</style>
