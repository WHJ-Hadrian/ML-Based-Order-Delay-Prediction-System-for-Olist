<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  DataAnalysis,
  DataLine,
  Delete,
  Document,
  FolderOpened,
  PieChart,
  Plus,
  Refresh,
  VideoPlay,
} from '@element-plus/icons-vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart as EchartsPieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getDatasetCatalog, listDatasets } from '@/api/datasets'
import {
  copyAnalysisTemplate,
  createAnalysisPlan,
  deleteAnalysisPlan,
  downloadAnalyticsReport,
  getAnalyticsCapabilities,
  listAnalysisPlans,
  listAnalysisTemplates,
  listAnalyticsValues,
  queryAnalytics,
  updateAnalysisPlan,
} from '@/api/analytics'

echarts.use([BarChart, LineChart, EchartsPieChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const datasets = ref([])
const datasetId = ref('')
const catalog = ref(null)
const tableId = ref('')
const capabilities = ref(null)

const selectedMetrics = ref([])
const selectedDimensions = ref([])
const timeColumn = ref('')
const timeGrain = ref('month')
const dateRange = ref([])
const filters = ref([{ column: '', values: [] }])
const limit = ref(200)

const kpi = ref(null)
const trend = ref(null)
const distribution = ref(null)
const detail = ref(null)
const running = ref(false)
const errorMessage = ref('')

const distributionType = ref('bar')
const trendType = ref('line')

const plans = ref([])
const templates = ref([])
const activePlanId = ref('')
const planName = ref('')
const saving = ref(false)
const reporting = ref('')

const trendRef = ref(null)
const distributionRef = ref(null)
let trendChart = null
let distributionChart = null

const grainOptions = [
  { value: 'month', label: '按月' },
  { value: 'week', label: '按周' },
  { value: 'day', label: '按日' },
  { value: 'quarter', label: '按季度' },
  { value: 'year', label: '按年' },
  { value: 'weekday', label: '按星期' },
  { value: 'hour', label: '按小时' },
]

const availableMetrics = computed(() => (capabilities.value?.metrics || []).filter((item) => item.available))
const dimensionOptions = computed(() => capabilities.value?.dimensions || [])
const timeOptions = computed(() => capabilities.value?.timeColumns || [])

const metricByKey = computed(() => {
  const map = {}
  for (const metric of availableMetrics.value) map[metric.key] = metric
  return map
})

const kpiCards = computed(() => {
  if (!kpi.value) return []
  return kpi.value.columns.map((column, index) => ({
    label: column.label,
    value: formatMetric(column.type, kpi.value.rows[0]?.[index]),
    hint: metricByKey.value[selectedMetrics.value[index]]?.description || '',
  }))
})

const detailColumns = computed(() => detail.value?.columns || [])
const detailRows = computed(() => detail.value?.rows || [])

function formatNumber(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) return '—'
  return number.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

function formatMetric(type, value) {
  if (value == null || value === '') return '—'
  if (type === 'percent') return `${(Number(value) * 100).toFixed(2)}%`
  if (type === 'duration') return `${Number(value).toFixed(2)} 天`
  return formatNumber(value)
}

function formatCell(column, cell) {
  if (cell == null) return '—'
  if (column.type === 'percent') return `${(Number(cell) * 100).toFixed(2)}%`
  if (column.type === 'duration') return `${Number(cell).toFixed(2)}`
  if (typeof cell === 'number') return formatNumber(cell)
  return String(cell)
}

async function loadDatasets() {
  try {
    const page = await listDatasets({ page: 0, size: 100 })
    datasets.value = page.items || []
    if (!datasetId.value && datasets.value.length) datasetId.value = datasets.value[0].datasetId
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function loadCatalog() {
  catalog.value = null
  tableId.value = ''
  capabilities.value = null
  if (!datasetId.value) return
  try {
    catalog.value = await getDatasetCatalog(datasetId.value)
    const tables = catalog.value?.tables || []
    if (tables.length) tableId.value = tables[0].tableId
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function loadCapabilities() {
  capabilities.value = null
  selectedMetrics.value = []
  selectedDimensions.value = []
  timeColumn.value = ''
  filters.value = [{ column: '', values: [] }]
  clearResults()
  if (!datasetId.value || !tableId.value) return
  try {
    capabilities.value = await getAnalyticsCapabilities(datasetId.value, tableId.value)
    selectedMetrics.value = (capabilities.value.metrics || [])
      .filter((item) => item.available)
      .slice(0, 3)
      .map((item) => item.key)
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function loadValues(target) {
  if (!target.column) return
  try {
    const body = await listAnalyticsValues({
      datasetVersionId: datasetId.value,
      tableId: tableId.value,
      column: target.column,
      limit: 100,
    })
    target.options = body.values || []
    target.values = []
  } catch (error) {
    ElMessage.error(error.message)
  }
}

function addFilter() {
  if (filters.value.length >= 3) return
  filters.value.push({ column: '', values: [] })
}

function removeFilter(index) {
  filters.value.splice(index, 1)
}

function baseSpec() {
  if (!datasetId.value || !tableId.value) throw new Error('请先选择数据集和数据表')
  if (!selectedMetrics.value.length) throw new Error('请至少选择一个指标')
  const spec = {
    source: { datasetVersionId: datasetId.value, tableId: tableId.value },
    metrics: selectedMetrics.value.map((key) => ({ ref: key })),
    limit: limit.value,
  }
  if (timeColumn.value) {
    spec.time = { column: timeColumn.value, grain: timeGrain.value }
    if (dateRange.value?.length === 2) {
      spec.time.range = { from: dateRange.value[0], to: dateRange.value[1] }
    }
  }
  const active = filters.value.filter((item) => item.column && item.values.length)
  if (active.length) {
    spec.filters = active.map((item) => ({ column: item.column, op: 'IN', values: item.values }))
  }
  return spec
}

async function runQuery(spec) {
  return queryAnalytics({ datasetVersionId: datasetId.value, tableId: tableId.value, spec })
}

function clearResults() {
  kpi.value = null
  trend.value = null
  distribution.value = null
  detail.value = null
  trendChart?.dispose()
  distributionChart?.dispose()
  trendChart = null
  distributionChart = null
}

async function run() {
  errorMessage.value = ''
  running.value = true
  try {
    const spec = baseSpec()
    const tasks = [runQuery({ ...spec, dimensions: undefined, time: undefined, orderBy: undefined, limit: 1 })]
    const withTime = Boolean(timeColumn.value)
    const withDimension = selectedDimensions.value.length > 0

    if (withTime) {
      tasks.push(runQuery({
        ...spec,
        dimensions: [],
        orderBy: [{ key: 'd1', dir: 'asc' }],
        limit: 500,
      }))
    }
    if (withDimension) {
      tasks.push(runQuery({
        ...spec,
        time: undefined,
        dimensions: [{ column: selectedDimensions.value[0], alias: dimensionLabel(selectedDimensions.value[0]) }],
        orderBy: [{ key: metricName(selectedMetrics.value[0]), dir: 'desc' }],
        limit: Math.min(limit.value, 100),
      }))
    }
    tasks.push(runQuery(spec))

    const [kpiResult, ...rest] = await Promise.all(tasks)
    let index = 0
    kpi.value = kpiResult
    trend.value = withTime ? rest[index++] : null
    distribution.value = withDimension ? rest[index++] : null
    detail.value = rest[index] || null
    await nextTick()
    renderCharts()
    ElMessage.success(`完成：${detail.value?.rowCount ?? 0} 行`)
  } catch (error) {
    errorMessage.value = error.message
    ElMessage.error(error.message)
  } finally {
    running.value = false
  }
}

function metricName(key) {
  return metricByKey.value[key]?.name || key
}

function dimensionLabel(column) {
  return column
}

function renderCharts() {
  renderTrend()
  renderDistribution()
}

function renderTrend() {
  if (!trendRef.value || !trend.value?.rows?.length) return
  if (!trendChart) trendChart = echarts.init(trendRef.value)
  const columns = trend.value.columns
  const dimensionIndex = 0
  const categories = trend.value.rows.map((row) => String(row[dimensionIndex] ?? ''))
  const series = columns
    .map((column, index) => ({ column, index }))
    .filter((item, index) => item.column.key.startsWith('m') && index < 2)
    .map(({ column, index }) => ({
      name: column.label,
      type: trendType.value === 'bar' ? 'bar' : 'line',
      smooth: trendType.value !== 'bar',
      symbolSize: 6,
      areaStyle: trendType.value === 'bar' ? undefined : { opacity: 0.12 },
      data: trend.value.rows.map((row) => Number(row[index])),
    }))
  trendChart.setOption({
    grid: { left: 44, right: 16, top: 34, bottom: 28 },
    tooltip: { trigger: 'axis' },
    legend: { top: 4, right: 8, textStyle: { fontSize: 11 } },
    xAxis: {
      type: 'category',
      data: categories,
      axisLabel: { fontSize: 10, color: '#7a8a80' },
      axisLine: { lineStyle: { color: '#d8e2da' } },
    },
    yAxis: {
      type: 'value',
      axisLabel: { fontSize: 10, color: '#7a8a80' },
      splitLine: { lineStyle: { color: '#eef4f0' } },
    },
    series,
  }, true)
}

function renderDistribution() {
  if (!distributionRef.value || !distribution.value?.rows?.length) return
  if (!distributionChart) distributionChart = echarts.init(distributionRef.value)
  const columns = distribution.value.columns
  const dimensionIndex = 0
  const metricIndex = columns.findIndex((column) => column.key.startsWith('m'))
  const rows = distribution.value.rows.slice(0, 12)
  const labels = rows.map((row) => String(row[dimensionIndex] ?? ''))
  const values = rows.map((row) => Number(row[metricIndex] ?? 0))
  const metricLabel = columns[metricIndex]?.label || ''

  if (distributionType.value === 'pie') {
    distributionChart.setOption({
      tooltip: { trigger: 'item', valueFormatter: (value) => formatNumber(value) },
      legend: { type: 'scroll', bottom: 0, textStyle: { fontSize: 10 } },
      series: [{
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        label: { fontSize: 10, formatter: '{b}\n{d}%' },
        data: labels.map((label, index) => ({ name: label, value: values[index] })),
      }],
    }, true)
    return
  }

  distributionChart.setOption({
    grid: { left: 96, right: 28, top: 16, bottom: 24 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', axisLabel: { fontSize: 10 }, splitLine: { lineStyle: { color: '#eef4f0' } } },
    yAxis: {
      type: 'category',
      data: labels,
      inverse: true,
      axisLabel: { fontSize: 10, color: '#4c5a52', width: 90, overflow: 'truncate' },
      axisLine: { lineStyle: { color: '#d8e2da' } },
    },
    series: [{
      name: metricLabel,
      type: 'bar',
      barMaxWidth: 14,
      itemStyle: { borderRadius: [0, 4, 4, 0], color: '#4f8f78' },
      data: values,
    }],
  }, true)
}

async function loadPlans() {
  try {
    plans.value = await listAnalysisPlans()
  } catch (error) {
    ElMessage.error(error.message)
  }
}

async function loadTemplates() {
  try {
    templates.value = await listAnalysisTemplates()
  } catch (error) {
    ElMessage.error(error.message)
  }
}

function resetState() {
  activePlanId.value = ''
  planName.value = ''
  selectedMetrics.value = (capabilities.value?.metrics || [])
    .filter((item) => item.available)
    .slice(0, 3)
    .map((item) => item.key)
  selectedDimensions.value = []
  timeColumn.value = ''
  timeGrain.value = 'month'
  dateRange.value = []
  filters.value = [{ column: '', values: [] }]
  clearResults()
}

function applyPlan(plan) {
  activePlanId.value = plan.id
  planName.value = plan.name
  const spec = plan.spec || {}
  selectedMetrics.value = (spec.metrics || []).map((item) => item.ref).filter(Boolean)
  selectedDimensions.value = (spec.dimensions || []).map((item) => item.column).filter(Boolean)
  timeColumn.value = spec.time?.column || ''
  timeGrain.value = spec.time?.grain || 'month'
  dateRange.value = spec.time?.range ? [spec.time.range.from, spec.time.range.to] : []
  filters.value = (spec.filters || []).map((item) => ({ column: item.column, values: [...(item.values || [])] }))
  if (!filters.value.length) filters.value = [{ column: '', values: [] }]
  limit.value = spec.limit || 200
  for (const filter of filters.value) {
    if (filter.column) loadValues(filter)
  }
}

async function openPlan(plan) {
  if (!capabilities.value) await loadCapabilities()
  applyPlan(plan)
  await run()
}

async function savePlan() {
  if (!planName.value.trim()) {
    ElMessage.warning('请填写方案名称')
    return
  }
  saving.value = true
  try {
    const spec = baseSpec()
    const payload = { name: planName.value.trim(), description: '', spec }
    const saved = activePlanId.value
      ? await updateAnalysisPlan(activePlanId.value, payload)
      : await createAnalysisPlan(payload)
    activePlanId.value = saved.id
    planName.value = saved.name
    await loadPlans()
    ElMessage.success('方案已保存')
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    saving.value = false
  }
}

async function removePlan(plan) {
  try {
    await ElMessageBox.confirm(`删除方案「${plan.name}」？`, '确认删除', { type: 'warning' })
    await deleteAnalysisPlan(plan.id)
    if (activePlanId.value === plan.id) resetState()
    await loadPlans()
    ElMessage.success('已删除')
  } catch (error) {
    if (error !== 'cancel') ElMessage.error(error.message || '删除失败')
  }
}

async function copyTemplate(template) {
  try {
    const plan = await copyAnalysisTemplate(template.key, datasetId.value, tableId.value)
    await loadPlans()
    applyPlan(plan)
    await run()
    ElMessage.success(`已复制模板：${template.name}`)
  } catch (error) {
    ElMessage.error(error.message)
  }
}

async function exportReport(format) {
  if (!detail.value) {
    ElMessage.warning('请先运行一次分析')
    return
  }
  reporting.value = format
  try {
    const spec = baseSpec()
    const { blob, filename } = await downloadAnalyticsReport(
      { datasetVersionId: datasetId.value, tableId: tableId.value, spec },
      format,
    )
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = filename
    anchor.click()
    URL.revokeObjectURL(url)
    ElMessage.success('报告已生成')
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    reporting.value = ''
  }
}

function exportCsv() {
  if (!detail.value) return
  const columns = detail.value.columns
  const header = columns.map((column) => column.label).join(',')
  const lines = detail.value.rows.map((row) => columns.map((column, index) => {
    const cell = row[index]
    const text = cell == null ? '' : formatCell(column, cell)
    return /[",\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text
  }).join(','))
  const blob = new Blob([`\ufeff${[header, ...lines].join('\n')}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `分析结果_${new Date().toISOString().slice(0, 19).replaceAll(':', '-')}.csv`
  anchor.click()
  URL.revokeObjectURL(url)
}

function resizeCharts() {
  trendChart?.resize()
  distributionChart?.resize()
}

watch(distributionType, () => renderDistribution())
watch(trendType, () => renderTrend())
watch(datasetId, async () => {
  await loadCatalog()
  await loadCapabilities()
})
watch(tableId, async (value, previous) => {
  if (value && value !== previous) await loadCapabilities()
})

onMounted(async () => {
  await loadDatasets()
  await loadCatalog()
  await loadCapabilities()
  await loadPlans()
  await loadTemplates()
  window.addEventListener('resize', resizeCharts)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  trendChart?.dispose()
  distributionChart?.dispose()
})
</script>

<template>
  <section class="workbench">
    <aside class="plans">
      <header>
        <h3>分析方案</h3>
        <el-button size="small" :icon="Plus" circle title="新建方案" @click="resetState" />
      </header>
      <ul class="plan-list">
        <li v-for="plan in plans" :key="plan.id" :class="{ active: plan.id === activePlanId }">
          <button type="button" class="plan-open" @click="openPlan(plan)">
            <el-icon><Document /></el-icon>
            <span>{{ plan.name }}</span>
            <small>v{{ plan.currentVersion }}</small>
          </button>
          <el-button size="small" text :icon="Delete" title="删除" @click="removePlan(plan)" />
        </li>
        <li v-if="!plans.length" class="empty">还没有保存的方案</li>
      </ul>
      <header class="template-header">
        <h3>系统模板</h3>
        <el-button size="small" text :icon="Refresh" title="刷新" @click="loadTemplates" />
      </header>
      <ul class="plan-list">
        <li v-for="template in templates" :key="template.key">
          <button type="button" class="plan-open" @click="copyTemplate(template)">
            <el-icon><FolderOpened /></el-icon>
            <span>{{ template.name }}</span>
          </button>
        </li>
      </ul>
    </aside>

    <main class="board">
      <header class="board-header">
        <div class="source">
          <el-select v-model="datasetId" placeholder="选择数据集" style="width: 250px">
            <el-option v-for="item in datasets" :key="item.datasetId" :label="item.name" :value="item.datasetId" />
          </el-select>
          <el-select v-model="tableId" placeholder="选择数据表" style="width: 190px">
            <el-option v-for="item in catalog?.tables || []" :key="item.tableId" :label="item.tableName" :value="item.tableId" />
          </el-select>
        </div>
        <div class="actions">
          <el-input v-model="planName" placeholder="方案名称" style="width: 170px" />
          <el-button :icon="Refresh" @click="resetState">重置</el-button>
          <el-button :icon="Document" :loading="saving" @click="savePlan">保存</el-button>
          <el-button type="primary" :icon="VideoPlay" :loading="running" @click="run">运行分析</el-button>
        </div>
      </header>

      <section class="config">
        <div class="config-block">
          <label>指标</label>
          <el-checkbox-group v-model="selectedMetrics" class="chips">
            <el-checkbox v-for="metric in availableMetrics" :key="metric.key" :value="metric.key">
              {{ metric.name }}
            </el-checkbox>
          </el-checkbox-group>
        </div>
        <div class="config-block">
          <label>分组维度（最多 3 个）</label>
          <el-select
            v-model="selectedDimensions"
            multiple
            :multiple-limit="3"
            collapse-tags
            placeholder="按维度分组"
            style="width: 100%"
          >
            <el-option v-for="column in dimensionOptions" :key="column.name" :label="column.name" :value="column.name" />
          </el-select>
        </div>
        <div class="config-block">
          <label>时间维度 / 粒度 / 范围</label>
          <div class="inline">
            <el-select v-model="timeColumn" clearable placeholder="不使用" style="width: 34%">
              <el-option v-for="column in timeOptions" :key="column.name" :label="column.name" :value="column.name" />
            </el-select>
            <el-select v-model="timeGrain" :disabled="!timeColumn" style="width: 20%">
              <el-option v-for="grain in grainOptions" :key="grain.value" :label="grain.label" :value="grain.value" />
            </el-select>
            <el-date-picker
              v-model="dateRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              start-placeholder="开始"
              end-placeholder="结束"
              style="flex: 1"
            />
          </div>
        </div>
        <div class="config-block">
          <label>
            筛选
            <el-button size="small" text :icon="Plus" @click="addFilter">添加</el-button>
          </label>
          <div v-for="(filter, index) in filters" :key="index" class="filter-row">
            <el-select v-model="filter.column" placeholder="字段" style="width: 140px" @change="loadValues(filter)">
              <el-option v-for="column in dimensionOptions" :key="column.name" :label="column.name" :value="column.name" />
            </el-select>
            <el-select
              v-model="filter.values"
              multiple
              collapse-tags
              filterable
              placeholder="取值"
              style="flex: 1"
              :disabled="!filter.column"
            >
              <el-option
                v-for="option in filter.options || []"
                :key="option.value"
                :label="`${option.value}（${option.count}）`"
                :value="option.value"
              />
            </el-select>
            <el-button v-if="filters.length > 1" text :icon="Delete" @click="removeFilter(index)" />
          </div>
        </div>
        <div class="config-block small">
          <label>返回行数</label>
          <el-select v-model="limit">
            <el-option :value="50" label="50" />
            <el-option :value="100" label="100" />
            <el-option :value="200" label="200" />
            <el-option :value="500" label="500" />
            <el-option :value="1000" label="1000" />
          </el-select>
        </div>
      </section>

      <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" />

      <section v-if="kpiCards.length" class="kpis">
        <article v-for="card in kpiCards" :key="card.label" class="kpi">
          <span class="kpi-label">{{ card.label }}</span>
          <strong class="kpi-value">{{ card.value }}</strong>
          <small v-if="card.hint" class="kpi-hint">{{ card.hint }}</small>
        </article>
      </section>

      <section v-if="kpiCards.length" class="grid-two">
        <article class="card" v-if="trend && trend.rows.length">
          <header class="card-header">
            <h3>趋势</h3>
            <el-radio-group v-model="trendType" size="small">
              <el-radio-button value="line">折线</el-radio-button>
              <el-radio-button value="bar">柱状</el-radio-button>
            </el-radio-group>
          </header>
          <div ref="trendRef" class="chart" />
        </article>
        <article class="card" v-if="distribution && distribution.rows.length">
          <header class="card-header">
            <h3>{{ dimensionOptions.length ? '分布' : '结果' }}</h3>
            <el-radio-group v-model="distributionType" size="small">
              <el-radio-button value="bar">条形</el-radio-button>
              <el-radio-button value="pie">占比</el-radio-button>
            </el-radio-group>
          </header>
          <div ref="distributionRef" class="chart" />
        </article>
      </section>

      <section v-if="detail" class="card detail">
        <header class="card-header">
          <h3>
            明细
            <small>{{ detail.rowCount }} 行 · {{ detail.elapsedMs }} ms</small>
          </h3>
          <div class="result-actions">
            <el-button size="small" :icon="DataAnalysis" @click="exportCsv">CSV</el-button>
            <el-button size="small" :loading="reporting === 'pdf'" @click="exportReport('pdf')">PDF</el-button>
            <el-button size="small" :loading="reporting === 'docx'" @click="exportReport('docx')">Word</el-button>
            <el-button size="small" :loading="reporting === 'html'" @click="exportReport('html')">HTML</el-button>
          </div>
        </header>
        <el-table :data="detailRows" size="small" border max-height="420">
          <el-table-column
            v-for="(column, index) in detailColumns"
            :key="column.key"
            :label="column.label"
            :min-width="130"
          >
            <template #default="{ row }">{{ formatCell(column, row[index]) }}</template>
          </el-table-column>
        </el-table>
      </section>

      <el-empty v-else-if="!running" description="选择指标和维度后点「运行分析」，会生成 KPI、趋势、分布和明细">
        <el-icon><DataLine /></el-icon>
      </el-empty>
    </main>
  </section>
</template>

<style scoped>
.workbench {
  display: grid;
  grid-template-columns: 250px 1fr;
  gap: 16px;
  height: 100%;
}

.plans {
  border-right: 1px solid var(--el-border-color-lighter);
  padding-right: 12px;
  overflow-y: auto;
}

.plans header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.plans h3 {
  font-size: 13px;
  margin: 0;
  color: var(--el-text-color-primary);
}

.plan-list {
  list-style: none;
  margin: 0 0 14px;
  padding: 0;
}

.plan-list li {
  display: flex;
  align-items: center;
  gap: 4px;
  border-radius: 6px;
}

.plan-list li.active {
  background: var(--el-color-primary-light-9);
}

.plan-open {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: 0;
  padding: 7px;
  cursor: pointer;
  text-align: left;
  color: var(--el-text-color-regular);
  font-size: 13px;
  overflow: hidden;
}

.plan-open span {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.plan-open small {
  color: var(--el-text-color-secondary);
}

.empty {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  padding: 6px 8px;
}

.template-header {
  margin-top: 10px;
  border-top: 1px dashed var(--el-border-color-lighter);
  padding-top: 10px;
}

.board {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  overflow-y: auto;
  padding-right: 4px;
}

.board-header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.source,
.actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.config {
  display: grid;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-blank);
}

.config-block label {
  display: block;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}

.inline {
  display: flex;
  gap: 8px;
  align-items: center;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.filter-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.config-block.small {
  max-width: 140px;
}

.kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
}

.kpi {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 14px 16px;
  background: linear-gradient(180deg, #fbfdfc, #ffffff);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.kpi-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.kpi-value {
  font-size: 24px;
  line-height: 1.2;
  color: #28503f;
  font-variant-numeric: tabular-nums;
}

.kpi-hint {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.grid-two {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 14px;
  background: var(--el-fill-color-blank);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.card-header h3 {
  margin: 0;
  font-size: 13px;
}

.card-header small {
  color: var(--el-text-color-secondary);
  font-weight: normal;
  margin-left: 6px;
}

.chart {
  width: 100%;
  height: 260px;
}

.detail {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.result-actions {
  display: flex;
  gap: 6px;
}

@media (max-width: 1200px) {
  .grid-two {
    grid-template-columns: 1fr;
  }
}
</style>
