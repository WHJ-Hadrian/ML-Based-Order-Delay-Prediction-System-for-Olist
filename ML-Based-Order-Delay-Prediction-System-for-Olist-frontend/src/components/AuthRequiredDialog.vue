<script setup>
defineProps({
  modelValue: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue', 'continue'])
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    class="auth-required-dialog"
    width="min(400px, calc(100vw - 32px))"
    align-center
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="auth-required-content">
      <h2>需要登录后继续</h2>
      <p>请先使用 SeekDateSet 账号登录，登录后即可继续访问数据资产。</p>
      <div class="auth-required-actions">
        <el-button @click="emit('update:modelValue', false)">取消</el-button>
        <el-button type="primary" @click="emit('continue')">继续登录</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.auth-required-content h2 {
  margin: 0;
  color: #192521;
  font-size: 18px;
  font-weight: 650;
}

.auth-required-content p {
  margin: 10px 0 24px;
  color: #68766f;
  font-size: 13px;
  line-height: 1.6;
}

.auth-required-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
