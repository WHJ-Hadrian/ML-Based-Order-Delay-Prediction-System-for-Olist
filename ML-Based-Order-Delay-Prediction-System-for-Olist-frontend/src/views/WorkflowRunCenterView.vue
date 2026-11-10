<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { CircleCheck, CircleClose, Loading, Refresh, Search, Share, VideoPlay } from '@element-plus/icons-vue'
import { cancelWorkflowRun, getWorkflowRun, retryWorkflowRun, searchWorkflowRuns } from '@/api/workflows'

const router = useRouter()
const runs = ref([])
const totalRuns = ref(0)
const totalPages = ref(0)
const currentPage = ref(1)
const pageSize = 20
const selectedRun = ref(null)
const selectedId = ref('')
const search = ref('')
const statusFilter = ref('ALL')
const loading = ref(false)
const detailLoading = ref(false)
const actionBusy = ref(false)
const error = ref('')
let pollTimer
let searchTimer
let detailSequence = 0
let listSequence = 0
let disposed = false

const activeRuns = computed(() => runs.value.filter((run) => isActive(run.status)).length)

function isActive(status) { return ['QUEUED', 'RUNNING', 'CANCELLING'].includes(status) }
function statusLabel(status) {
  return ({ QUEUED: '排队中', RUNNING: '运行中', CANCELLING: '正在取消', SUCCEEDED: '已完成', FAILED: '失败', CANCELED: '已取消' })[status] || status
}
function nodeStatusLabel(status) {
  return ({ QUEUED: '排队中', RUNNING: '运行中', SUCCEEDED: '完成', FAILED: '失败' })[status] || status
}
function formatTime(value) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '等待 Worker'
}
function formatClock(value) {
  return value ? new Date(value).toLocaleTimeString('zh-CN', { hour12: false }) : ''
}
function schedulePoll() {
  clearTimeout(pollTimer)
  if (disposed) return
  const delay = activeRuns.value || isActive(selectedRun.value?.status) ? 2500 : 15000
  pollTimer = setTimeout(() => refreshRuns({ silent: true }), delay)
}

function resetSelection() {
  detailSequence += 1
  selectedId.value = ''
  selectedRun.value = null
  detailLoading.value = false
}

async function refreshRuns({ silent = false } = {}) {
  clearTimeout(pollTimer)
  clearTimeout(searchTimer)
  const sequence = ++listSequence
  if (!silent) loading.value = true
  if (!silent) error.value = ''
  try {
    const result = await searchWorkflowRuns({
      page: currentPage.value - 1,
      size: pageSize,
      keyword: search.value,
      status: statusFilter.value,
    })
    if (sequence !== listSequence) return
    runs.value = result.items || []
    totalRuns.value = result.totalElements || 0
    totalPages.value = result.totalPages || 0
    if (currentPage.value > Math.max(1, totalPages.value)) {
      currentPage.value = Math.max(1, totalPages.value)
      return refreshRuns({ silent })
    }
    if (selectedId.value && !detailLoading.value && !actionBusy.value) await refreshSelectedRun()
    else if (runs.value.length) await selectRun(runs.value[0].id)
  } catch (cause) {
    if (sequence === listSequence) error.value = cause.message || '运行列表载入失败'
  } finally {
    if (sequence === listSequence) {
      loading.value = false
      schedulePoll()
    }
  }
}

function queueSearch() {
  listSequence += 1
  clearTimeout(pollTimer)
  runs.value = []
  totalRuns.value = 0
  totalPages.value = 0
  loading.value = true
  currentPage.value = 1
  resetSelection()
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => refreshRuns(), 300)
}

function changeStatus() {
  currentPage.value = 1
  resetSelection()
  refreshRuns()
}

function changePage(page) {
  currentPage.value = page
  resetSelection()
  refreshRuns()
}

async function refreshSelectedRun() {
  const id = selectedId.value
  const sequence = ++detailSequence
  try {
    const run = await getWorkflowRun(id)
    if (sequence === detailSequence && selectedId.value === id) selectedRun.value = run
  } catch (cause) {
    if (sequence === detailSequence && selectedId.value === id) error.value = cause.message || '运行详情刷新失败'
  }
}

async function retryList() {
  currentPage.value = 1
  await refreshRuns()
}

async function selectRun(id) {
  const sequence = ++detailSequence
  selectedId.value = id
  selectedRun.value = null
  detailLoading.value = true
  error.value = ''
  try {
    const run = await getWorkflowRun(id)
    if (sequence === detailSequence) selectedRun.value = run
  } catch (cause) {
    if (sequence === detailSequence) error.value = cause.message || '运行详情载入失败'
  } finally {
    if (sequence === detailSequence) {
      detailLoading.value = false
      schedulePoll()
    }
  }
}

async function actOnRun(action) {
  if (!selectedRun.value || actionBusy.value) return
  const targetId = selectedRun.value.id
  const sequence = ++detailSequence
  clearTimeout(pollTimer)
  actionBusy.value = true
  error.value = ''
  try {
    const updated = action === 'cancel'
      ? await cancelWorkflowRun(targetId)
      : await retryWorkflowRun(targetId)
    if (disposed || selectedId.value !== targetId || sequence !== detailSequence) return
    selectedRun.value = updated
    if (action === 'retry') {
      selectedId.value = updated.id
      currentPage.value = 1
      search.value = ''
      statusFilter.value = 'ALL'
    }
    await refreshRuns()
  } catch (cause) {
    if (!disposed && selectedId.value === targetId && sequence === detailSequence) {
      error.value = cause.message || (action === 'cancel' ? '取消运行失败' : '重试运行失败')
    }
  } finally {
    actionBusy.value = false
    schedulePoll()
  }
}

function openWorkflow() {
  if (!selectedRun.value) return
  router.push({ path: '/workflows', query: { workflow: selectedRun.value.workflowId } })
}

onMounted(refreshRuns)
onBeforeUnmount(() => {
  disposed = true
  clearTimeout(pollTimer)
  clearTimeout(searchTimer)
  listSequence += 1
  detailSequence += 1
})
</script>

<template>
  <main class="run-center">
    <header class="run-center-heading">
      <div>
        <h1>运行中心</h1>
        <p>跨工作流追踪后台运行、查看冻结结果并处理失败任务。</p>
      </div>
      <div class="run-center-health"><span class="health-dot" />本页 {{ activeRuns }} 个运行中</div>
    </header>

    <div v-if="error" class="run-center-error" role="alert"><span>{{ error }}</span><el-button text :icon="Refresh" @click="retryList">重试</el-button></div>

    <section class="run-center-workspace" :class="{ 'is-list-loading': loading && !runs.length }" aria-label="工作流运行中心">
      <aside class="run-center-list-panel">
        <div class="run-center-tools">
          <el-input v-model="search" clearable :maxlength="100" :prefix-icon="Search" placeholder="按工作流名称搜索" aria-label="按工作流名称搜索" @input="queueSearch" />
          <el-select v-model="statusFilter" aria-label="筛选运行状态" @change="changeStatus">
            <el-option label="全部状态" value="ALL" />
            <el-option label="排队中" value="QUEUED" />
            <el-option label="运行中" value="RUNNING" />
            <el-option label="正在取消" value="CANCELLING" />
            <el-option label="已完成" value="SUCCEEDED" />
            <el-option label="失败" value="FAILED" />
            <el-option label="已取消" value="CANCELED" />
          </el-select>
        </div>
        <div class="run-center-list-heading"><strong>运行记录</strong><span>显示 {{ runs.length }} 条 · 共 {{ totalRuns }} 条</span><el-button text circle :icon="Refresh" :loading="loading" aria-label="刷新运行列表" title="刷新运行列表" @click="refreshRuns" /></div>
        <nav v-if="runs.length" class="global-run-list" aria-label="工作流运行列表">
          <button v-for="run in runs" :key="run.id" type="button" class="global-run-item" :class="{ 'is-selected': selectedId === run.id }" :title="`${run.workflowName} · ${run.id}`" @click="selectRun(run.id)">
            <span class="global-run-status" :class="`status-${run.status.toLowerCase()}`"><el-icon><Loading v-if="isActive(run.status)" /><CircleCheck v-else-if="run.status === 'SUCCEEDED'" /><CircleClose v-else /></el-icon></span>
            <span class="global-run-copy"><strong>{{ run.workflowName }}</strong><small>{{ statusLabel(run.status) }} · 修订 {{ run.workflowRevision }}</small><small>{{ formatTime(run.createdAt) }}</small></span>
            <span v-if="isActive(run.status)" class="global-run-progress">{{ run.progress }}%</span>
          </button>
        </nav>
        <div v-else-if="loading" class="run-center-empty">正在读取运行记录</div>
        <div v-else class="run-center-empty">{{ totalRuns ? '当前页没有运行记录' : search.trim() || statusFilter !== 'ALL' ? '没有符合筛选条件的运行' : '还没有工作流运行记录' }}</div>
        <el-pagination
          v-if="totalPages > 1"
          class="run-center-pagination"
          :current-page="currentPage"
          :page-size="pageSize"
          :total="totalRuns"
          :pager-count="5"
          layout="prev, pager, next"
          @current-change="changePage"
        />
      </aside>

      <section class="run-center-detail" aria-label="运行详情">
        <div v-if="detailLoading || (loading && !runs.length)" class="run-center-empty run-center-loading"><el-icon><Loading /></el-icon><span>正在读取运行记录</span></div>
        <template v-else-if="selectedRun">
          <header class="run-detail-top">
            <div class="run-detail-title">
              <span class="run-detail-kicker">工作流运行 · 修订 {{ selectedRun.workflowRevision }}</span>
              <h2 :title="selectedRun.workflowName">{{ selectedRun.workflowName }}</h2>
              <span class="run-detail-id" :title="selectedRun.id">{{ selectedRun.id }}</span>
            </div>
            <div class="run-detail-commands">
              <el-button v-if="isActive(selectedRun.status)" plain type="danger" :loading="actionBusy" :icon="CircleClose" @click="actOnRun('cancel')">取消</el-button>
              <el-button v-if="['FAILED', 'CANCELED'].includes(selectedRun.status)" plain :loading="actionBusy" :icon="Refresh" @click="actOnRun('retry')">按原快照重试</el-button>
              <el-button :icon="Share" @click="openWorkflow">打开画布</el-button>
            </div>
          </header>
          <div class="run-detail-metrics">
            <div><small>状态</small><strong :class="`text-${selectedRun.status.toLowerCase()}`">{{ statusLabel(selectedRun.status) }}</strong></div>
            <div><small>进度</small><strong>{{ selectedRun.progress }}%</strong></div>
            <div><small>创建时间</small><strong>{{ formatTime(selectedRun.createdAt) }}</strong></div>
            <div><small>开始时间</small><strong>{{ formatTime(selectedRun.startedAt) }}</strong></div>
            <div><small>完成时间</small><strong>{{ formatTime(selectedRun.finishedAt) }}</strong></div>
          </div>
          <el-progress :percentage="selectedRun.progress" :status="selectedRun.status === 'FAILED' ? 'exception' : selectedRun.status === 'SUCCEEDED' ? 'success' : undefined" :stroke-width="7" :show-text="false" />
          <div v-if="selectedRun.errorMessage" class="run-center-failure" role="alert">{{ selectedRun.errorMessage }}</div>
          <div class="run-snapshot-note"><el-icon><VideoPlay /></el-icon><span>节点结果来自本次运行冻结的定义修订，不随当前画布后续修改而变化。</span></div>
          <section class="run-node-section">
            <header><strong>节点执行</strong><span>{{ selectedRun.nodes?.length || 0 }} 个节点</span></header>
            <article v-for="node in selectedRun.nodes || []" :key="node.nodeId" class="global-node-result">
              <header><span class="global-node-name" :title="node.nodeId">{{ node.nodeLabel || node.nodeId }}</span><span class="global-node-type">{{ node.nodeType }}</span><span class="global-node-status">{{ nodeStatusLabel(node.status) }}</span></header>
              <p v-if="node.errorMessage" class="run-center-failure">{{ node.errorMessage }}</p>
              <ol v-if="node.logs?.length" class="global-node-logs" aria-label="节点运行日志">
                <li v-for="(log, index) in node.logs" :key="`${log.timestamp}-${index}`" :class="{ 'is-error': log.level === 'ERROR' }"><time :datetime="new Date(log.timestamp).toISOString()">{{ formatClock(log.timestamp) }}</time><span>{{ log.message }}</span></li>
              </ol>
              <template v-if="node.result">
                <div class="global-result-stats"><span>{{ node.result.tableName || node.result.nodeType }}</span><span>{{ Number(node.result.rowCount ?? node.result.sampleInputRows ?? 0).toLocaleString('zh-CN') }} 行</span><span>{{ node.result.columnCount ?? node.result.columns?.length ?? 0 }} 个字段</span><span v-if="node.result.sampleMatchedRows != null">样本匹配 {{ node.result.sampleMatchedRows }} / {{ node.result.sampleInputRows }}</span></div>
                <div v-if="node.result.sampleColumns?.length" class="global-sample-scroll" tabindex="0">
                  <table><thead><tr><th v-for="(column, index) in node.result.sampleColumns" :key="`${index}-${column}`" :title="column">{{ column }}</th></tr></thead><tbody><tr v-for="(row, rowIndex) in node.result.sampleRows || []" :key="rowIndex"><td v-for="(column, columnIndex) in node.result.sampleColumns" :key="`${rowIndex}-${columnIndex}`" :title="row[columnIndex] ?? ''">{{ row[columnIndex] ?? '空' }}</td></tr></tbody></table>
                </div>
              </template>
            </article>
            <p v-if="!selectedRun.nodes?.length" class="run-center-empty">{{ selectedRun.status === 'QUEUED' ? '等待 Worker 接收运行' : '此运行没有节点结果' }}</p>
          </section>
        </template>
        <div v-else-if="!runs.length" class="run-center-empty run-center-welcome"><el-icon><Share /></el-icon><strong>还没有运行记录</strong><span>创建或运行工作流后，状态、节点日志和结果会显示在这里。</span><el-button size="small" :icon="VideoPlay" @click="router.push('/workflows')">打开工作流</el-button></div>
        <div v-else class="run-center-empty run-center-welcome"><el-icon><Share /></el-icon><strong>选择一次运行</strong><span>查看状态、节点日志和本次运行保存的结果。</span></div>
      </section>
    </section>
  </main>
</template>

<style scoped>
.run-center { display: grid; min-width: 0; gap: 10px; color: #242424; }
.run-center-heading { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 14px; }
.run-center-heading h1 { margin: 0; font-size: 20px; line-height: 1.3; }
.run-center-heading p { margin: 5px 0 0; color: #718078; font-size: 12px; }
.run-center-health { display: flex; flex: 0 0 auto; align-items: center; gap: 7px; color: #5f7067; font-size: 11px; }
.health-dot { width: 8px; height: 8px; border-radius: 50%; background: #41936b; box-shadow: 0 0 0 3px #e5f2ea; }
.run-center-workspace { display: grid; min-width: 0; min-height: max(420px, calc(100dvh - 190px)); grid-template-columns: minmax(260px, 300px) minmax(0, 1fr); overflow: hidden; background: #fff; border: 1px solid var(--line-strong); border-radius: var(--radius-panel); }
.run-center-list-panel { display: flex; min-width: 0; flex-direction: column; background: #fff; border-right: 1px solid #e5ece8; }
.run-center-tools { display: grid; gap: 6px; padding: 8px 9px; border-bottom: 1px solid #e1e1e1; }
.run-center-tools > *, .run-center-tools :deep(.el-input), .run-center-tools :deep(.el-select), .run-center-tools :deep(.el-select__wrapper) { width: 100%; min-width: 0; }
.run-center-list-heading { display: flex; min-width: 0; align-items: center; gap: 7px; padding: 6px 9px; color: #354a3f; }
.run-center-list-heading strong { font-size: 12px; }
.run-center-list-heading > span { flex: 1; color: #87938c; font-size: 10px; }
.global-run-list { display: grid; align-content: start; max-height: calc(100vh - 255px); overflow: auto; }
.run-center-pagination { flex: 0 0 auto; justify-content: center; padding: 10px 6px; border-top: 1px solid #edf1ee; }
.run-center-pagination :deep(.el-pager li), .run-center-pagination :deep(button) { min-width: 24px; height: 24px; font-size: 10px; }
.global-run-item { display: flex; width: 100%; min-width: 0; align-items: center; gap: 8px; padding: 8px 9px; color: #405149; text-align: left; background: #fff; border: 0; border-bottom: 1px solid #edf1ee; cursor: pointer; }
.global-run-item:hover { background: #f7faf8; }
.global-run-item.is-selected { background: #e7f1fb; }
.global-run-status { display: grid; width: 26px; height: 26px; flex: 0 0 auto; place-items: center; color: #70777e; background: #f2f3f4; border-radius: 4px; }
.status-running, .status-queued, .status-cancelling { color: #2c8061; background: #e5f3eb; }
.status-succeeded { color: #27784d; background: #e6f4e9; }
.status-failed { color: #b53e37; background: #f9eae8; }
.status-canceled { color: #7a817d; background: #eef0ef; }
.global-run-copy { display: grid; min-width: 0; flex: 1; gap: 3px; }
.global-run-copy strong, .global-run-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.global-run-copy strong { color: #354a3f; font-size: 11px; }
.global-run-copy small { color: #87938c; font-size: 9px; }
.global-run-progress { flex: 0 0 auto; color: #4c7562; font-size: 10px; }
.run-center-detail { display: grid; min-width: 0; align-content: start; gap: 9px; padding: 12px 14px; background: #fff; }
.run-detail-top { display: flex; min-width: 0; align-items: flex-start; justify-content: space-between; gap: 16px; }
.run-detail-title { display: grid; min-width: 0; gap: 4px; }
.run-detail-kicker { color: #87938c; font-size: 10px; }
.run-detail-title h2 { max-width: 100%; margin: 0; overflow: hidden; color: #263a31; font-size: 17px; text-overflow: ellipsis; white-space: nowrap; }
.run-detail-id { max-width: 100%; overflow: hidden; color: #97a29c; font: 9px ui-monospace, monospace; text-overflow: ellipsis; white-space: nowrap; }
.run-detail-commands { display: flex; flex: 0 0 auto; flex-wrap: wrap; justify-content: flex-end; gap: 6px; }
.run-detail-metrics { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 8px; padding: 11px 0; border-top: 1px solid #edf1ee; border-bottom: 1px solid #edf1ee; }
.run-detail-metrics > div { display: grid; min-width: 0; gap: 4px; }
.run-detail-metrics small { color: #89948e; font-size: 9px; }
.run-detail-metrics strong { overflow: hidden; color: #405149; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.text-succeeded { color: #27784d !important; }
.text-failed { color: #b53e37 !important; }
.text-running, .text-queued, .text-cancelling { color: #357b5a !important; }
.text-canceled { color: #7a817d !important; }
.run-center-failure { margin: 0; padding: 9px 11px; color: #a13d37; font-size: 11px; line-height: 1.55; overflow-wrap: anywhere; background: #fbefee; border-left: 3px solid #ca6b60; }
.run-snapshot-note { display: flex; min-width: 0; align-items: flex-start; gap: 7px; color: #6d7e74; font-size: 10px; line-height: 1.5; }
.run-snapshot-note .el-icon { flex: 0 0 auto; color: #4b8266; }
.run-node-section { display: grid; min-width: 0; gap: 9px; }
.run-node-section > header { display: flex; align-items: center; gap: 8px; color: #354a3f; }
.run-node-section > header strong { font-size: 12px; }
.run-node-section > header span { color: #89948e; font-size: 10px; }
.global-node-result { display: grid; min-width: 0; gap: 9px; padding: 11px; border: 1px solid #e4ebe7; border-radius: 5px; }
.global-node-result > header { display: flex; min-width: 0; align-items: center; gap: 8px; }
.global-node-name { min-width: 0; overflow: hidden; color: #34493f; font-size: 11px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.global-node-type { flex: 0 0 auto; padding: 3px 5px; color: #6c7d73; font: 8px ui-monospace, monospace; background: #f0f4f1; }
.global-node-status { flex: 0 0 auto; margin-left: auto; color: #718078; font-size: 9px; }
.global-node-logs { display: grid; max-height: 130px; gap: 4px; margin: 0; padding: 8px; overflow: auto; list-style: none; background: #f6f8f7; }
.global-node-logs li { display: grid; min-width: 0; grid-template-columns: 58px minmax(0, 1fr); gap: 7px; color: #65736b; font-size: 9px; line-height: 1.45; }
.global-node-logs time { color: #89958e; font-variant-numeric: tabular-nums; }
.global-node-logs li span { overflow-wrap: anywhere; }
.global-node-logs li.is-error { color: #a13d37; }
.global-result-stats { display: flex; flex-wrap: wrap; gap: 6px 14px; color: #64746b; font-size: 9px; }
.global-sample-scroll { max-width: 100%; overflow: auto; border: 1px solid #e8eeea; }
.global-sample-scroll table { width: 100%; border-collapse: collapse; font-size: 9px; }
.global-sample-scroll th, .global-sample-scroll td { min-width: 90px; max-width: 240px; padding: 6px 8px; overflow: hidden; text-align: left; text-overflow: ellipsis; white-space: nowrap; border-bottom: 1px solid #edf1ee; }
.global-sample-scroll th { color: #5c6f63; background: #f4f7f5; }
.run-center-empty { display: grid; min-height: 120px; place-items: center; padding: 20px; color: #87938c; font-size: 11px; text-align: center; }
.run-center-welcome { align-content: center; justify-items: center; gap: 9px; }
.run-center-welcome .el-icon { color: #598c70; font-size: 26px; }
.run-center-welcome strong { color: #44564d; }
.run-center-welcome span { max-width: 360px; line-height: 1.6; }
.run-center-welcome :deep(.el-button) { margin-top: 5px; }
.run-center-loading { align-content: center; gap: 9px; }
.run-center-loading .el-icon { color: #328064; font-size: 18px; animation: run-center-spin 1s linear infinite; }
@keyframes run-center-spin { to { transform: rotate(360deg); } }
.run-center-error { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 8px 12px; color: #a13d37; font-size: 11px; overflow-wrap: anywhere; background: #fbefee; border-left: 3px solid #ca6b60; }
@media (max-width: 900px) {
  .run-center-workspace { grid-template-columns: minmax(230px, 34%) minmax(0, 1fr); }
  .run-detail-top { flex-direction: column; }
  .run-detail-commands { justify-content: flex-start; }
  .run-detail-metrics { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@media (max-width: 760px) {
  .run-center-heading { align-items: flex-start; flex-direction: column; }
  .run-center-heading p { line-height: 1.5; }
  .run-center-workspace { min-height: 0; grid-template-columns: minmax(0, 1fr); }
  .run-center-workspace.is-list-loading { min-height: 0; }
  .run-center-workspace.is-list-loading .run-center-detail { display: none; }
  .run-center-list-panel { max-height: 380px; border-right: 0; border-bottom: 1px solid #e5ece8; }
  .global-run-list { max-height: 250px; }
  .run-center-detail { padding: 13px; }
  .run-detail-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .run-detail-commands { width: 100%; }
  .run-detail-commands :deep(.el-button) { flex: 1; margin-left: 0; }
}
</style>
