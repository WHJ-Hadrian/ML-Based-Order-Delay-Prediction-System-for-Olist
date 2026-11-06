<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowDown, ArrowUp, CircleCheck, CircleClose, Loading, Refresh } from '@element-plus/icons-vue'
import { searchWorkflowRuns } from '@/api/workflows'
import { useAuth } from '@/auth/keycloak'
import { useWorkbench } from '@/state/workbench'

const router = useRouter()
const auth = useAuth()
const { state, toggleBottomPanel } = useWorkbench()
const runs = ref([])
const loading = ref(false)
const error = ref('')
let timer
let disposed = false

const activeCount = computed(() => runs.value.filter((run) => ['QUEUED', 'RUNNING', 'CANCELLING'].includes(run.status)).length)

function statusIcon(status) {
  if (['QUEUED', 'RUNNING', 'CANCELLING'].includes(status)) return Loading
  if (status === 'SUCCEEDED') return CircleCheck
  return CircleClose
}

function statusLabel(status) {
  return ({ QUEUED: '排队中', RUNNING: '运行中', CANCELLING: '取消中', SUCCEEDED: '完成', FAILED: '失败', CANCELED: '已取消' })[status] || status
}

async function refresh() {
  clearTimeout(timer)
  if (disposed || !auth.state.authenticated) return
  loading.value = true
  error.value = ''
  try {
    const result = await searchWorkflowRuns({ page: 0, size: 6 })
    if (disposed || !auth.state.authenticated) return
    runs.value = result.items || []
  } catch (cause) {
    if (disposed) return
    error.value = cause.message || '运行记录载入失败'
  } finally {
    loading.value = false
    if (!disposed && auth.state.authenticated) timer = setTimeout(refresh, activeCount.value ? 5000 : 15000)
  }
}

function openRunCenter() {
  router.push('/workflow-runs')
}

watch(() => auth.state.authenticated, (authenticated) => {
  clearTimeout(timer)
  if (authenticated) refresh()
  else {
    runs.value = []
    error.value = ''
  }
}, { immediate: true })

onBeforeUnmount(() => {
  disposed = true
  clearTimeout(timer)
})
</script>

<template>
  <section class="bottom-panel" :class="{ 'is-open': state.bottomPanelOpen }" aria-label="任务面板">
    <header class="bottom-panel-bar">
      <button type="button" class="panel-tab is-active" @click="state.bottomPanelOpen || toggleBottomPanel()">
        任务 <span v-if="activeCount">{{ activeCount }}</span>
      </button>
      <button type="button" class="panel-refresh" aria-label="刷新任务" title="刷新任务" :disabled="loading" @click="refresh"><el-icon :class="{ 'is-spinning': loading }"><Refresh /></el-icon></button>
      <button type="button" class="panel-toggle" :aria-label="state.bottomPanelOpen ? '收起任务面板' : '展开任务面板'" @click="toggleBottomPanel">
        <el-icon><ArrowDown v-if="state.bottomPanelOpen" /><ArrowUp v-else /></el-icon>
      </button>
    </header>
    <div v-if="state.bottomPanelOpen" class="bottom-panel-body">
      <button v-for="run in runs" :key="run.id" type="button" class="task-row" @click="openRunCenter">
        <el-icon :class="{ 'is-spinning': ['QUEUED', 'RUNNING', 'CANCELLING'].includes(run.status) }"><component :is="statusIcon(run.status)" /></el-icon>
        <strong>{{ run.workflowName || '未命名工作流' }}</strong>
        <span>{{ statusLabel(run.status) }}</span>
        <small>{{ run.createdAt ? new Date(run.createdAt).toLocaleString('zh-CN', { hour12: false }) : '' }}</small>
      </button>
      <button v-if="!loading && !error && !runs.length" type="button" class="task-empty" @click="openRunCenter">暂无运行任务</button>
      <button v-if="error" type="button" class="task-empty is-error" @click="refresh">{{ error }}</button>
    </div>
  </section>
</template>

<style scoped>
.bottom-panel { flex: 0 0 32px; min-width: 0; background: #f8faf9; border-top: 1px solid #dce3df; }
.bottom-panel.is-open { flex-basis: 150px; }
.bottom-panel-bar { display: flex; height: 32px; align-items: center; gap: 4px; padding: 0 8px; }
.panel-tab, .panel-refresh, .panel-toggle { height: 26px; color: #65736c; font-size: 10px; background: transparent; border: 0; border-radius: 4px; cursor: pointer; }
.panel-tab { position: relative; padding: 0 9px; }
.panel-tab.is-active { color: #214d3e; font-weight: 650; }
.panel-tab.is-active::after { position: absolute; right: 7px; bottom: -3px; left: 7px; height: 2px; background: #28775f; content: ''; }
.panel-tab span { min-width: 16px; margin-left: 4px; padding: 1px 4px; color: #fff; background: #28775f; border-radius: 8px; }
.panel-refresh { margin-left: auto; }
.panel-toggle { width: 28px; }
.panel-refresh:hover, .panel-toggle:hover { color: #265c49; background: #e8eeeb; }
.bottom-panel-body { display: grid; height: 118px; align-content: start; overflow: auto; padding: 4px 8px 8px; border-top: 1px solid #edf1ef; }
.task-row { display: grid; min-width: 0; grid-template-columns: 18px minmax(120px, 1fr) 70px 150px; align-items: center; gap: 7px; min-height: 27px; padding: 0 7px; color: #526159; font-size: 10px; text-align: left; background: transparent; border: 0; border-radius: 4px; cursor: pointer; }
.task-row:hover { background: #edf3ef; }
.task-row strong, .task-row small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.task-row strong { color: #34483f; font-weight: 600; }
.task-row small { color: #89938e; }
.task-empty { min-height: 56px; color: #87928d; font-size: 10px; background: transparent; border: 0; cursor: pointer; }
.task-empty.is-error { color: #9a4940; }
.is-spinning { animation: panel-spin 1s linear infinite; }
@keyframes panel-spin { to { transform: rotate(360deg); } }
@media (max-width: 760px) {
  .bottom-panel { position: fixed; right: 8px; bottom: calc(88px + env(safe-area-inset-bottom)); left: 8px; z-index: 15; max-width: 560px; margin-inline: auto; border: 1px solid #dce3df; border-radius: 7px; box-shadow: 0 8px 28px rgba(26, 48, 39, .14); }
  .bottom-panel.is-open { max-height: min(34dvh, 220px); }
  .bottom-panel-body { height: auto; max-height: calc(min(34dvh, 220px) - 33px); }
  .task-row { grid-template-columns: 18px minmax(0, 1fr) 60px; }
  .task-row small { display: none; }
}
</style>
