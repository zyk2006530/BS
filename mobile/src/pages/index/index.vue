<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { getHealth, type HealthResponse } from '../../api/health';
const health = ref<HealthResponse | null>(null);
const loading = ref(false);
const error = ref('');
async function refresh() {
  if (loading.value) return;
  loading.value = true;
  health.value = null;
  error.value = '';
  try {
    health.value = await getHealth(import.meta.env.VITE_API_BASE_URL);
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '健康检查失败';
  } finally {
    loading.value = false;
  }
}
onMounted(refresh);
</script>

<template>
  <view class="page">
    <text class="eyebrow">TASK-01 / SKELETON</text>
    <text class="title">企业审批系统 · 工程骨架</text>
    <text class="paragraph"
      >当前仅有微信小程序健康壳，四类申请、审批和通知尚未实现。</text
    >
    <view class="card">
      <text class="heading">服务健康</text>
      <text class="paragraph">GET /api/v1/health</text>
      <text v-if="loading" class="paragraph">正在检查服务…</text>
      <text v-else-if="error" class="error">{{ error }}</text>
      <view v-else-if="health">
        <text class="paragraph">状态：{{ health.status }}</text>
        <text class="paragraph">阶段：{{ health.stage }}</text>
        <text class="paragraph"
          >业务可用：{{ String(health.businessReady) }}（尚不可办理）</text
        >
      </view>
      <button
        class="refresh"
        :disabled="loading"
        :loading="loading"
        @click="refresh"
      >
        重新检查
      </button>
    </view>
  </view>
</template>
<style scoped>
.page {
  padding: 16px;
  padding-bottom: calc(16px + env(safe-area-inset-bottom));
  overflow-wrap: anywhere;
}
.eyebrow {
  display: block;
  color: #34587d;
  font-weight: 700;
}
.title {
  display: block;
  font-size: 24px;
  font-weight: 700;
  margin: 16px 0;
}
.paragraph,
.error {
  display: block;
  margin: 12px 0;
  word-break: break-all;
}
.card {
  background: white;
  border: 1px solid #c9d5df;
  border-radius: 12px;
  padding: 16px;
}
.heading {
  font-size: 20px;
  font-weight: 700;
}
.error {
  color: #a02816;
}
button {
  min-height: 44px;
  font-size: 16px;
  margin-top: 16px;
}
</style>
