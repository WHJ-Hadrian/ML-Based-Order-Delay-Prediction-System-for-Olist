<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Setting } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const isSettingsPage = computed(() => route.name === 'settings')

function openSettings() {
  router.push({ name: 'settings' })
}
</script>

<template>
  <button
    v-if="!isSettingsPage"
    type="button"
    class="dock-fab"
    aria-label="打开设置"
    title="设置"
    @click="openSettings"
  >
    <el-icon><Setting /></el-icon>
    <span>设置</span>
  </button>
</template>

<style scoped>
.dock-fab {
  position: fixed;
  right: 24px;
  bottom: 22px;
  z-index: 1590;
  display: inline-flex;
  height: 42px;
  align-items: center;
  gap: 7px;
  padding: 0 16px;
  color: #f3fffa;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.01em;
  background: rgba(23, 107, 87, 0.94);
  border: 1px solid rgba(255, 255, 255, 0.36);
  border-radius: 999px;
  box-shadow:
    0 12px 30px rgba(10, 56, 45, 0.24),
    inset 0 1px 0 rgba(255, 255, 255, 0.22);
  cursor: pointer;
  backdrop-filter: blur(14px);
  transition:
    transform 0.18s ease,
    background 0.18s ease,
    box-shadow 0.18s ease;
}

.dock-fab:hover {
  background: #176b57;
  box-shadow:
    0 15px 34px rgba(10, 56, 45, 0.28),
    inset 0 1px 0 rgba(255, 255, 255, 0.26);
  transform: translateY(-2px);
}

.dock-fab:active {
  transform: translateY(0);
}

.dock-fab .el-icon {
  font-size: 15px;
}

@media (max-width: 640px) {
  .dock-fab {
    right: 16px;
    bottom: 16px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .dock-fab {
    transition: none;
  }
}
</style>
