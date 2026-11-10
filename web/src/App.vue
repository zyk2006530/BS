<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { getHealth, type HealthResponse } from './api/health';
const health = ref<HealthResponse | null>(null);
const loading = ref(false);
const error = ref('');
async function refresh() {
  if (loading.value) return;
  loading.value = true;
  health.value = null;
  error.value = '';
  try {
    health.value = await getHealth();
  } catch (cause) {
    error.value =
      cause instanceof Error
        ? cause.message
        : '无法连接健康接口，请检查同源 API 配置';
  } finally {
    loading.value = false;
  }
}
onMounted(refresh);
</script>

<template>
  <main>
    <p class="eyebrow">TASK-01 / SKELETON</p>
    <h1>企业审批系统 · 工程骨架</h1>
    <p>当前仅验证构建与健康接口，采购、审批等业务尚未实现。</p>
    <section aria-labelledby="health-title" :aria-busy="loading">
      <h2 id="health-title">服务健康</h2>
      <p><code>GET /api/v1/health</code></p>
      <p v-if="loading" role="status">正在检查服务…</p>
      <p v-else-if="error" role="alert">{{ error }}</p>
      <dl v-else-if="health" aria-live="polite">
        <dt>状态</dt>
        <dd>{{ health.status }}</dd>
        <dt>阶段</dt>
        <dd>{{ health.stage }}</dd>
        <dt>业务可用</dt>
        <dd>{{ String(health.businessReady) }}（尚不可办理）</dd>
      </dl>
      <button type="button" :disabled="loading" @click="refresh">
        {{ loading ? '检查中…' : '重新检查' }}
      </button>
    </section>
  </main>
</template>
