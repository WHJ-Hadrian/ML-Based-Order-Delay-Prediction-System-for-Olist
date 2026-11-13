<template>
  <main class="page-content delay-page">
    <h1 class="visually-hidden">延误分析</h1>

    <header class="page-header delay-header">
      <div class="delay-title">
        <strong>物流延误分析</strong>
        <span>整体指标 · 签收结构 · 维度延误率</span>
      </div>
      <div class="delay-filters">
        <el-select v-model="selectedDatasetId" filterable size="small" placeholder="选择数据集" class="filter-select" :loading="loadingDatasets">
          <el-option v-for="dataset in datasets" :key="dataset.datasetId" :value="dataset.datasetId" :label="dataset.name">
            <span class="option-label" :title="dataset.name">{{ dataset.name }}</span>
            <small class="option-meta">{{ formatNumber(dataset.rowCount) }} 行</small>
          </el-option>
        </el-select>
        <el-select v-model="selectedTableId" size="small" placeholder="选择数据表" class="filter-select" :disabled="!catalog" :loading="loadingCatalog">
          <el-option v-for="table in tables" :key="table.tableId" :value="table.tableId" :label="table.tableName">
            <span class="option-label" :title="table.tableName">{{ table.tableName }}</span>
            <small class="option-meta">{{ formatNumber(table.rowCount) }} 行</small>
          </el-option>
        </el-select>
        <el-button circle size="small" :icon="Refresh" :loading="loading" aria-label="刷新分析" title="刷新" @click="loadAnalysis" />
      </div>
    </header>

    <div v-if="error" class="delay-message is-error" role="alert">
      <el-icon><Warning /></el-icon><span>{{ error }}</span>
      <el-button text @click="loadAnalysis">重试</el-button>
    </div>

    <div v-if="!data && loading" class="delay-loading">正在统计…</div>

    <template v-if="data">
      <div class="delay-source">
        <span>{{ data.tableName }}</span>
        <span>版本 v{{ versionNo }}</span>
        <span>{{ formatNumber(data.rowCount) }} 行</span>
        <el-tag v-if="data.derivedFromTimestamps" size="small" effect="plain" type="info">延误由时间戳推导</el-tag>
        <span v-if="unavailable.length" class="source-warn">缺少字段：{{ unavailable.join('、') }}</span>
      </div>

      <section class="kpi-grid" aria-label="订单统计">
        <article class="kpi-card">
          <span class="kpi-label">订单量</span>
          <strong>{{ formatNumber(data.totals.orders) }}</strong>
          <small>数据表全部下单记录</small>
        </article>
        <article class="kpi-card">
          <span class="kpi-label">已签收订单量</span>
          <strong>{{ formatNumber(data.totals.delivered) }}</strong>
          <small>有实际签收时间</small>
        </article>
        <article class="kpi-card">
          <span class="kpi-label">延误订单量</span>
          <strong>{{ formatNumber(data.totals.delayed) }}</strong>
          <small>签收晚于承诺日期</small>
        </article>
        <article class="kpi-card is-accent">
          <span class="kpi-label">整体延误率</span>
          <strong>{{ percent(data.totals.delayRate) }}</strong>
          <small>延误量 ÷ 订单量</small>
        </article>
      </section>

      <section class="kpi-grid is-three" aria-label="物流延误指标">
        <article class="kpi-card">
          <span class="kpi-label">平均配送耗时</span>
          <strong>{{ days(data.metrics.avgDeliveryDays) }}</strong>
          <small>下单 → 签收，均值</small>
        </article>
        <article class="kpi-card">
          <span class="kpi-label">延误率</span>
          <strong>{{ percent(data.metrics.delayRate) }}</strong>
          <small>已签收订单中延误占比</small>
        </article>
        <article class="kpi-card">
          <span class="kpi-label">平均延误天数</span>
          <strong>{{ days(data.metrics.avgDelayDays) }}</strong>
          <small>仅统计延误订单，晚到多久</small>
        </article>
      </section>

      <section class="delay-panel" aria-label="签收结果分布">
        <div class="panel-heading">
          <div class="panel-title"><h2>整体物流水平</h2><span>提前 / 按时 / 延误的结构，判断配送是否稳定</span></div>
          <el-tag size="small" effect="plain" :type="stability.type">{{ stability.label }}</el-tag>
        </div>
        <div class="outcome-list">
          <div v-for="item in outcomes" :key="item.key" class="outcome-row">
            <span class="outcome-label">{{ item.label }}</span>
            <div class="outcome-track">
              <div class="outcome-bar" :class="`is-${item.key}`" :style="{ width: `${item.width}%` }" />
            </div>
            <span class="outcome-value">{{ formatNumber(item.count) }}</span>
            <span class="outcome-rate">{{ percent(item.rate) }}</span>
          </div>
        </div>
        <p class="panel-note">
          提前送达指签收早于承诺日期；按时送达指签收日期与承诺日期相同；延误指晚于承诺日期。
          提前占比越高、延误波动越小，说明该数据集的配送承诺越保守、履约越稳定。
        </p>
      </section>

      <div class="dimension-grid">
        <section class="delay-panel" aria-label="按月延误率">
          <div class="panel-heading">
            <div class="panel-title"><h2>按月份</h2><span>下单月份 × 延误率（12 个月汇总）</span></div>
          </div>
          <div class="bar-chart">
            <div v-for="item in data.byMonth" :key="item.month" class="bar-column">
              <span class="bar-value">{{ percent(item.delayRate) }}</span>
              <div class="bar-track">
                <div class="bar-fill" :class="rateClass(item.delayRate)" :style="{ height: `${barHeight(item.delayRate)}%` }" />
              </div>
              <span class="bar-label">{{ item.month }}月</span>
              <span class="bar-sub">{{ formatNumber(item.orders) }}</span>
            </div>
          </div>
        </section>

        <section class="delay-panel" aria-label="按星期延误率">
          <div class="panel-heading">
            <div class="panel-title"><h2>按星期</h2><span>下单星期 × 延误率</span></div>
          </div>
          <div class="bar-chart">
            <div v-for="item in data.byWeekday" :key="item.weekday" class="bar-column">
              <span class="bar-value">{{ percent(item.delayRate) }}</span>
              <div class="bar-track">
                <div class="bar-fill" :class="rateClass(item.delayRate)" :style="{ height: `${barHeight(item.delayRate)}%` }" />
              </div>
              <span class="bar-label">{{ item.label || item.weekday }}</span>
              <span class="bar-sub">{{ formatNumber(item.orders) }}</span>
            </div>
          </div>
        </section>
      </div>

      <section class="delay-panel" aria-label="按商品类目延误率">
        <div class="panel-heading">
          <div class="panel-title"><h2>按商品类目</h2><span>订单量前 12 的类目，按延误率排序</span></div>
        </div>
        <el-table :data="sortedCategories" size="small" table-layout="fixed" class="category-table">
          <el-table-column prop="category" label="商品类目" min-width="200" show-overflow-tooltip />
          <el-table-column label="订单量" width="110" align="right">
            <template #default="{ row }">{{ formatNumber(row.orders) }}</template>
          </el-table-column>
          <el-table-column label="延误量" width="100" align="right">
            <template #default="{ row }">{{ formatNumber(row.delayed) }}</template>
          </el-table-column>
          <el-table-column label="延误率" width="220">
            <template #default="{ row }">
              <div class="table-rate">
                <div class="table-rate-track">
                  <div class="table-rate-fill" :class="rateClass(row.delayRate)" :style="{ width: `${Math.min(100, (row.delayRate || 0) * 400)}%` }" />
                </div>
                <span :class="rateClass(row.delayRate)">{{ percent(row.delayRate) }}</span>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <p class="panel-note">延误率明显高于整体的类目，是优先排查的对象（类目可能集中在重货、家具、跨境卖家等环节）。</p>
      </section>

      <section class="delay-panel" aria-label="相关性分析">
        <div class="panel-heading">
          <div class="panel-title"><h2>配送时长与用户评分</h2><span>相关系数（非必要项）</span></div>
          <el-tag size="small" effect="plain" :type="data.correlation.available ? 'success' : 'info'">
            {{ data.correlation.available ? '已计算' : '不可用' }}
          </el-tag>
        </div>
        <div v-if="data.correlation.available" class="correlation-value">
          <strong>{{ formatNumber(data.correlation.deliveryVsRating, 3) }}</strong>
          <span>{{ correlationText }}</span>
          <small v-if="data.correlation.scope?.startsWith('joined:')">
            关联 {{ data.correlation.scope.slice(7) }} 计算
          </small>
        </div>
        <p v-else class="panel-note">{{ data.correlation.reason }}</p>
      </section>
    </template>

    <div v-else-if="!loading && !error" class="delay-empty">
      请选择一个包含 <code>is_delayed</code> 字段的数据表。
    </div>
  </main>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { Refresh, Warning } from '@element-plus/icons-vue'
import { getDatasetCatalog, getDelayAnalysis, listDatasets } from '@/api/datasets'
import { useAuth } from '@/auth/keycloak'

const auth = useAuth()
const datasets = ref([])
const catalog = ref(null)
const selectedDatasetId = ref('')
const selectedTableId = ref('')
const data = ref(null)
const loading = ref(false)
const loadingDatasets = ref(false)
const loadingCatalog = ref(false)
const error = ref('')

const tables = computed(() => catalog.value?.tables || [])
const versionNo = computed(() => catalog.value?.versionNo ?? '—')
const unavailable = computed(() => {
  if (!data.value) return []
  const derived = Boolean(data.value.derivedFromTimestamps)
  const skip = derived ? new Set(['is_delayed', 'delay_days', 'total_delivery_days']) : new Set()
  return Object.entries(data.value.columns || {})
    .filter(([name, physical]) => !physical && name !== 'review_score' && !skip.has(name))
    .map(([name]) => name)
})

const outcomes = computed(() => {
  if (!data.value) return []
  const o = data.value.outcomes
  const total = (o.early || 0) + (o.onTime || 0) + (o.delayed || 0) || 1
  return [
    { key: 'early', label: '提前送达', count: o.early, rate: o.earlyRate, width: ((o.early || 0) / total) * 100 },
    { key: 'ontime', label: '按时送达', count: o.onTime, rate: o.onTimeRate, width: ((o.onTime || 0) / total) * 100 },
    { key: 'delayed', label: '延误', count: o.delayed, rate: o.delayedRate, width: ((o.delayed || 0) / total) * 100 },
  ]
})

const stability = computed(() => {
  const rate = data.value?.totals?.delayRate ?? 0
  if (rate >= 0.15) return { label: '延误偏高', type: 'danger' }
  if (rate >= 0.08) return { label: '需要关注', type: 'warning' }
  return { label: '整体稳定', type: 'success' }
})

const sortedCategories = computed(() => {
  const rows = [...(data.value?.byCategory || [])]
  return rows.sort((left, right) => (right.delayRate || 0) - (left.delayRate || 0))
})

const correlationText = computed(() => {
  const value = data.value?.correlation?.deliveryVsRating
  if (value == null) return '样本不足'
  if (value <= -0.3) return '中等负相关：配送越慢，评分越低'
  if (value < -0.1) return '弱负相关：配送时长对评分有轻微影响'
  if (value < 0.1) return '几乎不相关'
  return '正相关：需要结合业务复核'
})

const maxRate = computed(() => {
  const rates = [
    ...(data.value?.byMonth || []).map((item) => item.delayRate || 0),
    ...(data.value?.byWeekday || []).map((item) => item.delayRate || 0),
  ]
  return Math.max(0.01, ...rates)
})

function barHeight(rate) {
  return Math.max(3, ((rate || 0) / maxRate.value) * 100)
}

function rateClass(rate) {
  const value = Number(rate || 0)
  if (value >= 0.15) return 'is-high'
  if (value >= 0.08) return 'is-mid'
  return 'is-low'
}

function percent(value) {
  if (value == null || Number.isNaN(Number(value))) return '—'
  return `${(Number(value) * 100).toFixed(2)}%`
}

function days(value) {
  if (value == null || Number.isNaN(Number(value))) return '—'
  return `${Number(value).toFixed(2)} 天`
}

function formatNumber(value, digits = 0) {
  if (value == null || Number.isNaN(Number(value))) return '—'
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: digits }).format(Number(value))
}

function autoPickTable(list) {
  const tables = list || []
  return tables.find((table) => (table.columns || []).some((column) => column.name === 'is_delayed'))
    || tables.find((table) => /orders/i.test(table.tableName))
    || tables[0]
    || null
}

async function loadDatasets() {
  if (!auth.state.authenticated) return
  loadingDatasets.value = true
  error.value = ''
  try {
    const result = await listDatasets({ status: 'READY', page: 0, size: 100 })
    datasets.value = result?.items || []
    if (!datasets.value.length) return
    if (!datasets.value.some((dataset) => dataset.datasetId === selectedDatasetId.value)) {
      const preferred = datasets.value.find((dataset) => /ml_ready|延误训练集|train70/i.test(dataset.name))
      selectedDatasetId.value = (preferred || datasets.value[0]).datasetId
    }
  } catch (failure) {
    error.value = failure.message || '数据集载入失败'
  } finally {
    loadingDatasets.value = false
  }
}

async function loadCatalog() {
  if (!selectedDatasetId.value) return
  loadingCatalog.value = true
  catalog.value = null
  data.value = null
  error.value = ''
  try {
    catalog.value = await getDatasetCatalog(selectedDatasetId.value)
    const table = autoPickTable(catalog.value?.tables)
    if (!table) {
      error.value = '该数据集版本没有可分析的数据表。'
      return
    }
    selectedTableId.value = table.tableId
  } catch (failure) {
    error.value = failure.message || '数据表载入失败'
  } finally {
    loadingCatalog.value = false
  }
}

async function loadAnalysis() {
  if (!selectedDatasetId.value || !selectedTableId.value) return
  loading.value = true
  error.value = ''
  try {
    data.value = await getDelayAnalysis(selectedDatasetId.value, selectedTableId.value)
  } catch (failure) {
    error.value = failure.message || '延误分析失败'
    data.value = null
  } finally {
    loading.value = false
  }
}

onMounted(loadDatasets)
watch(() => auth.state.status, (status) => {
  if (status === 'authenticated') loadDatasets()
  if (status === 'anonymous') {
    datasets.value = []
    catalog.value = null
    data.value = null
    selectedDatasetId.value = ''
    selectedTableId.value = ''
  }
})
watch(selectedDatasetId, loadCatalog)
watch(selectedTableId, loadAnalysis)
</script>

<style scoped>
.delay-page { display: grid; gap: 12px; padding-bottom: 26px; }
.delay-header { align-items: center; }
.delay-title { display: grid; gap: 3px; }
.delay-title strong { color: #26352e; font-size: 18px; font-weight: 680; }
.delay-title span { color: #6b7a72; font-size: 11px; }
.delay-filters { display: flex; align-items: center; gap: 8px; }
.filter-select { width: 220px; }
.delay-source { display: flex; flex-wrap: wrap; gap: 6px 14px; color: #7b8a82; font-size: 10px; }
.source-warn { color: #a34733; }
.kpi-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.kpi-grid.is-three { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.kpi-card { display: grid; gap: 4px; padding: 12px 14px; background: #fff; border: 1px solid var(--line); border-radius: 8px; }
.kpi-card.is-accent { background: #f4f8f5; border-color: #cfe0d6; }
.kpi-label { color: #7b8a82; font-size: 10px; }
.kpi-card strong { color: #26352e; font-size: 22px; font-weight: 700; letter-spacing: 0; }
.kpi-card small { color: #93a29a; font-size: 9px; }
.delay-panel { padding: 12px 14px; background: #fff; border: 1px solid var(--line); border-radius: 8px; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 10px; }
.panel-title { display: flex; align-items: baseline; gap: 8px; }
.panel-title h2 { margin: 0; color: #26352e; font-size: 13px; font-weight: 660; }
.panel-title span { color: #859289; font-size: 10px; }
.panel-note { margin: 8px 0 0; color: #93a29a; font-size: 9px; line-height: 1.6; }
.outcome-list { display: grid; gap: 8px; }
.outcome-row { display: grid; grid-template-columns: 74px minmax(0, 1fr) 70px 62px; align-items: center; gap: 8px; }
.outcome-label { color: #5c6c64; font-size: 10px; }
.outcome-track { height: 10px; overflow: hidden; background: #edf2ef; border-radius: 5px; }
.outcome-bar { height: 100%; border-radius: 5px; }
.outcome-bar.is-early { background: #4f8a68; }
.outcome-bar.is-ontime { background: #9bbf7a; }
.outcome-bar.is-delayed { background: #d9694f; }
.outcome-value { color: #4c5f55; font-size: 10px; text-align: right; }
.outcome-rate { color: #263d33; font-size: 11px; font-weight: 620; text-align: right; }
.dimension-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.bar-chart { display: flex; align-items: flex-end; gap: 6px; height: 150px; padding-top: 14px; }
.bar-column { display: grid; flex: 1; grid-template-rows: auto minmax(0, 1fr) auto auto; align-items: end; gap: 3px; text-align: center; }
.bar-value { color: #6b7a72; font-size: 8px; }
.bar-track { display: flex; height: 92px; align-items: flex-end; background: #f3f6f4; border-radius: 3px; }
.bar-fill { width: 100%; border-radius: 3px 3px 0 0; }
.bar-fill.is-low { background: #6ea287; }
.bar-fill.is-mid { background: #d8b25c; }
.bar-fill.is-high { background: #cf6a4f; }
.bar-label { color: #5c6c64; font-size: 9px; }
.bar-sub { color: #a3b0a8; font-size: 8px; }
.category-table { font-size: 10px; }
.table-rate { display: grid; grid-template-columns: minmax(0, 1fr) 54px; align-items: center; gap: 8px; }
.table-rate-track { height: 8px; overflow: hidden; background: #edf2ef; border-radius: 4px; }
.table-rate-fill { height: 100%; border-radius: 4px; }
.table-rate-fill.is-low { background: #6ea287; }
.table-rate-fill.is-mid { background: #d8b25c; }
.table-rate-fill.is-high { background: #cf6a4f; }
.table-rate span { font-size: 10px; text-align: right; }
.table-rate span.is-high { color: #a34733; }
.table-rate span.is-mid { color: #8a6a1f; }
.table-rate span.is-low { color: #2f6a4f; }
.correlation-value { display: flex; align-items: baseline; gap: 10px; }
.correlation-value strong { color: #26352e; font-size: 26px; font-weight: 700; }
.correlation-value span { color: #6b7a72; font-size: 11px; }
.correlation-value small { color: #93a29a; font-size: 9px; }
.delay-message { display: flex; align-items: center; gap: 8px; padding: 8px 10px; color: #8d3c35; background: #fff4f0; border: 1px solid #f1d2c8; border-radius: 6px; font-size: 11px; }
.delay-message span { flex: 1; }
.delay-loading, .delay-empty { padding: 40px 12px; color: #859289; font-size: 11px; text-align: center; }
@media (max-width: 1080px) {
  .kpi-grid, .kpi-grid.is-three { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .dimension-grid { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 760px) {
  .kpi-grid, .kpi-grid.is-three { grid-template-columns: minmax(0, 1fr); }
  .delay-filters { flex-wrap: wrap; }
  .filter-select { width: 100%; }
  .outcome-row { grid-template-columns: 64px minmax(0, 1fr) 60px 56px; }
}
</style>
