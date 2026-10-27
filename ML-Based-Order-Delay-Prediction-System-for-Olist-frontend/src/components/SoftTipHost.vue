<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Bell } from '@element-plus/icons-vue'
import {
  DEFAULT_SOFT_TIP_SECONDS,
  MAX_SOFT_TIP_SECONDS,
  MIN_SOFT_TIP_SECONDS,
  getSoftTipPageDefinition,
} from '@/config/softTips'
import { useSoftTipSettings } from '@/state/softTips'

const TIP_VISIBLE_MS = 14000
const BLOCKED_RETRY_MS = 8000
const ACTIVITY_THROTTLE_MS = 320
const POINTER_MOVE_THRESHOLD = 18

const route = useRoute()
const { settings } = useSoftTipSettings()

const activeTip = ref(null)
const visible = ref(false)
const pageKey = computed(() => String(route.meta.navigationKey || ''))
const pageDefinition = computed(() => getSoftTipPageDefinition(pageKey.value))
const pageTitle = computed(() => pageDefinition.value?.title || '')

let idleTimer = null
let dismissTimer = null
let retryTimer = null
let shownSinceActivity = false
let pendingTip = null
let lastActivityAt = 0
let lastPointerPosition = null
const lastTipKeys = {}

function clearIdleTimer() {
  if (idleTimer) {
    window.clearTimeout(idleTimer)
    idleTimer = null
  }
}

function clearRetryTimer() {
  if (retryTimer) {
    window.clearTimeout(retryTimer)
    retryTimer = null
  }
}

function clearDismissTimer() {
  if (dismissTimer) {
    window.clearTimeout(dismissTimer)
    dismissTimer = null
  }
}

function clearTimers() {
  clearIdleTimer()
  clearRetryTimer()
  clearDismissTimer()
}

function isDocumentActive() {
  return (
    !document.hidden &&
    document.visibilityState === 'visible' &&
    document.hasFocus()
  )
}

function isElementBlocking(element) {
  if (!element) {
    return false
  }
  const style = window.getComputedStyle(element)
  if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') {
    return false
  }
  const rect = element.getBoundingClientRect()
  return rect.width > 0 && rect.height > 0
}

function hasBlockingLayer() {
  const selectors = [
    '.extract-guide-layer',
    '.el-overlay',
    '.el-message-box',
    '.el-loading-mask',
  ]
  return selectors.some((selector) =>
    Array.from(document.querySelectorAll(selector)).some(isElementBlocking),
  )
}

function currentPageSettings() {
  return settings.value.pages?.[pageKey.value] || null
}

function canShowTips() {
  return Boolean(
    settings.value.enabled &&
    pageDefinition.value &&
    currentPageSettings()?.enabled &&
    isDocumentActive(),
  )
}

function clampDelay(value) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) {
    return DEFAULT_SOFT_TIP_SECONDS
  }
  return Math.min(MAX_SOFT_TIP_SECONDS, Math.max(MIN_SOFT_TIP_SECONDS, Math.round(parsed)))
}

function pickTip() {
  const definition = pageDefinition.value
  const pageSettings = currentPageSettings()
  if (!definition || !pageSettings) {
    return null
  }
  const candidates = definition.tips.filter(
    (tip) => pageSettings.tips?.[tip.key]?.enabled !== false,
  )
  if (!candidates.length) {
    return null
  }
  const previousKey = lastTipKeys[pageKey.value]
  const pool = candidates.length > 1
    ? candidates.filter((tip) => tip.key !== previousKey)
    : candidates
  const tip = pool[Math.floor(Math.random() * pool.length)]
  lastTipKeys[pageKey.value] = tip.key
  return tip
}

function getTipDelaySeconds(tip) {
  const customDelay = currentPageSettings()?.tips?.[tip.key]?.delaySeconds
  return clampDelay(customDelay ?? settings.value.idleSeconds)
}

function hideTip() {
  clearDismissTimer()
  visible.value = false
  activeTip.value = null
}

function showTip(tip) {
  activeTip.value = tip
  visible.value = true
  shownSinceActivity = true
  clearDismissTimer()
  dismissTimer = window.setTimeout(() => {
    dismissTimer = null
    hideTip()
  }, TIP_VISIBLE_MS)
}

function tryShowTip(tip) {
  if (!tip || visible.value || shownSinceActivity || !canShowTips()) {
    return
  }
  if (hasBlockingLayer()) {
    clearRetryTimer()
    retryTimer = window.setTimeout(() => {
      retryTimer = null
      tryShowTip(tip)
    }, BLOCKED_RETRY_MS)
    return
  }
  showTip(tip)
}

function scheduleIdleTip() {
  clearIdleTimer()
  clearRetryTimer()
  pendingTip = null
  if (visible.value || shownSinceActivity || !canShowTips()) {
    return
  }
  const tip = pickTip()
  if (!tip) {
    return
  }
  pendingTip = tip
  idleTimer = window.setTimeout(() => {
    idleTimer = null
    const nextTip = pendingTip
    pendingTip = null
    tryShowTip(nextTip)
  }, getTipDelaySeconds(tip) * 1000)
}

function restartIdleClock() {
  clearTimers()
  if (visible.value) {
    hideTip()
  }
  shownSinceActivity = false
  lastActivityAt = Date.now()
  scheduleIdleTip()
}

function handleActivity(event) {
  if (event?.type === 'pointermove') {
    const nextPosition = { x: event.clientX, y: event.clientY }
    const previous = lastPointerPosition
    lastPointerPosition = nextPosition
    if (!previous) {
      return
    }
    const distance = Math.hypot(
      nextPosition.x - previous.x,
      nextPosition.y - previous.y,
    )
    if (distance < POINTER_MOVE_THRESHOLD) {
      return
    }
  }
  const now = Date.now()
  if (now - lastActivityAt < ACTIVITY_THROTTLE_MS) {
    return
  }
  restartIdleClock()
}

function handleVisibilityChange() {
  clearTimers()
  hideTip()
  shownSinceActivity = false
  if (!document.hidden && document.hasFocus()) {
    lastActivityAt = Date.now()
    scheduleIdleTip()
  }
}

function handleWindowBlur() {
  clearTimers()
  hideTip()
}

function handleWindowFocus() {
  if (document.hidden || !document.hasFocus()) {
    return
  }
  restartIdleClock()
}

function handlePageHide() {
  clearTimers()
  hideTip()
}

function handleRouteChange() {
  lastPointerPosition = null
  restartIdleClock()
}

watch(pageKey, handleRouteChange)

watch(
  settings,
  () => {
    restartIdleClock()
  },
  { deep: true },
)

onMounted(() => {
  window.addEventListener('pointermove', handleActivity, { passive: true })
  window.addEventListener('pointerdown', handleActivity, { passive: true })
  window.addEventListener('keydown', handleActivity, { passive: true })
  window.addEventListener('wheel', handleActivity, { passive: true })
  window.addEventListener('touchstart', handleActivity, { passive: true })
  window.addEventListener('scroll', handleActivity, { passive: true, capture: true })
  window.addEventListener('blur', handleWindowBlur)
  window.addEventListener('focus', handleWindowFocus)
  window.addEventListener('pagehide', handlePageHide)
  document.addEventListener('visibilitychange', handleVisibilityChange)
  restartIdleClock()
})

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', handleActivity)
  window.removeEventListener('pointerdown', handleActivity)
  window.removeEventListener('keydown', handleActivity)
  window.removeEventListener('wheel', handleActivity)
  window.removeEventListener('touchstart', handleActivity)
  window.removeEventListener('scroll', handleActivity, { capture: true })
  window.removeEventListener('blur', handleWindowBlur)
  window.removeEventListener('focus', handleWindowFocus)
  window.removeEventListener('pagehide', handlePageHide)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  clearTimers()
})
</script>

<template>
  <Transition name="soft-tip">
    <aside
      v-if="visible && activeTip"
      class="soft-tip-card"
      role="status"
      aria-live="polite"
    >
      <span class="soft-tip-icon" aria-hidden="true">
        <el-icon><Bell /></el-icon>
      </span>
      <div class="soft-tip-copy">
        <div class="soft-tip-meta">
          <span class="soft-tip-label">小提示</span>
          <span v-if="pageTitle" class="soft-tip-page">{{ pageTitle }}</span>
          <span class="soft-tip-settings-hint">可在右下角设置中关闭</span>
        </div>
        <strong class="soft-tip-title">{{ activeTip.title }}</strong>
        <p class="soft-tip-text">{{ activeTip.text }}</p>
      </div>
    </aside>
  </Transition>
</template>

<style scoped>
.soft-tip-card {
  position: fixed;
  right: 24px;
  bottom: 88px;
  z-index: 1580;
  display: flex;
  width: min(372px, calc(100vw - 32px));
  gap: 12px;
  padding: 14px 16px 15px;
  color: var(--ink);
  background: rgba(255, 255, 255, 0.965);
  border: 1px solid rgba(23, 107, 87, 0.16);
  border-radius: 16px;
  box-shadow:
    0 20px 48px rgba(8, 21, 18, 0.16),
    inset 0 1px 0 rgba(255, 255, 255, 0.92);
  pointer-events: none;
  backdrop-filter: blur(18px);
}

.soft-tip-card::before {
  position: absolute;
  top: 15px;
  bottom: 15px;
  left: 0;
  width: 3px;
  background: linear-gradient(180deg, #1d8a6f, #79bfa7);
  border-radius: 0 3px 3px 0;
  content: '';
}

.soft-tip-icon {
  display: grid;
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  place-items: center;
  margin-top: 1px;
  color: #176b57;
  background: #e4f4ed;
  border-radius: 9px;
}

.soft-tip-icon .el-icon {
  font-size: 15px;
}

.soft-tip-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.soft-tip-meta {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--ink-faint);
  font-size: 10px;
  letter-spacing: 0.04em;
}

.soft-tip-label {
  color: #176b57;
  font-weight: 650;
}

.soft-tip-page::before {
  margin-right: 7px;
  content: '·';
}

.soft-tip-settings-hint {
  margin-left: auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.soft-tip-title {
  color: var(--ink);
  font-size: 13px;
  font-weight: 650;
  line-height: 1.45;
}

.soft-tip-text {
  margin: 0;
  color: var(--ink-soft);
  font-size: 12px;
  line-height: 1.65;
}

.soft-tip-enter-active {
  transition:
    opacity 0.28s ease,
    transform 0.34s cubic-bezier(0.22, 0.8, 0.24, 1);
}

.soft-tip-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.2s ease;
}

.soft-tip-enter-from,
.soft-tip-leave-to {
  opacity: 0;
  transform: translateY(10px);
}

@media (max-width: 640px) {
  .soft-tip-card {
    right: 16px;
    bottom: 78px;
    width: calc(100vw - 32px);
  }

  .soft-tip-settings-hint {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .soft-tip-enter-active,
  .soft-tip-leave-active {
    transition: opacity 0.16s ease;
  }

  .soft-tip-enter-from,
  .soft-tip-leave-to {
    transform: none;
  }
}
</style>
