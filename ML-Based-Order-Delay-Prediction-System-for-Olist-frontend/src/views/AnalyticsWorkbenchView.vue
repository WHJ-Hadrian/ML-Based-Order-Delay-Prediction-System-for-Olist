<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DataAnalysis, Delete, Document, FolderOpened, Plus, Refresh, VideoPlay } from '@element-plus/icons-vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart, ScatterChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getDatasetCatalog, listDatasets } from '@/api/datasets'
import {
  copyAnalysisTemplate,
  createAnalysisPlan,
  deleteAnalysisPlan,
  downloadAnalyticsReport,
  executeAnalytics,
  getAnalyticsCapabilities,
  listAnalysisPlans,
  listAnalysisTemplates,
  listAnalyticsValues,
  updateAnalysisPlan,
} from '@/api/analytics'

echarts.use([BarChart, LineChart, PieChart, ScatterChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

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

const visuals = ref([])
const results = ref([])
const running = ref(false)
const errorMessage = ref('')

const plans = ref([])
const templates = ref([])
const activePlanId = ref('')
const planName = ref('')
const saving = ref(false)
const reporting = ref('')

const charts = new Map()

const grainOptions = [
  { value: 'month', label: '按月' },
  { value: 'week', label: '按周' },
  { value: 'day', label: '按日' },
  { value: 'quarter', label: '按季度' },
  { value: 'year', label: '按年' },
  { value: 'weekday', label: '按星期' },
  { value: 'hour', label: '按小时' },
]

const visualTypes = [
  { value: 'kpi', label: '指标卡' },
  { value: 'line', label: '折线图' },
  { value: 'bar', label: '柱状/条形' },
  { value: 'pie', label: '占比环图' },
  { value: 'scatter', label: '散点（相关性）' },
  { value: 'table', label: '明细表' },
]

const availableMetrics = computed(() => (capabilities.value?.metrics || []).filter((item) => item.available))
const dimensionOptions = computed(() => capabilities.value?.dimensions || [])
const timeOptions = computed(() => capabilities.value?.timeColumns || [])
const fieldOptions = computed(() => capabilities.value?.fields || [])

function metricName(key) {
  return availableMetrics.value.find((item) => item.key === key)?.name || key
}

function formatNumber(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) return '—'
  return number.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

function formatCell(column, cell) {
  if (cell == null) return '—'
  if (column?.type === 'percent') return `${(Number(cell) * 100).toFixed(2)}%`
  if (column?.type === 'duration') return `${Number(cell).toFixed(2)}`
  if (typeof cell === 'number') return formatNumber(cell)
  return String(cell)
}

function defaultVisuals() {
  const list = [{ id: 'kpi', type: 'kpi', title: '核心指标', width: 'full' }]
  const metric = selectedMetrics.value[0]
  if (timeColumn.value) {
    list.push({ id: 'trend', type: 'line', title: '趋势', metric, dimension: '__time__', width: 'half' })
  }
  if (selectedDimensions.value.length) {
    list.push({
      id: 'distribution',
      type: 'bar',
      title: '分布',
      metric,
      dimension: selectedDimensions.value[0],
      width: 'half',
    })
  }
  list.push({ id: 'detail', type: 'table', title: '明细', width: 'full' })
  return list
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
    visuals.value = defaultVisuals()
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
  if (selectedDimensions.value.length) {
    spec.dimensions = selectedDimensions.value.map((column) => ({ column }))
  }
  spec.visuals = visuals.value.map((visual) => ({
    id: visual.id,
    type: visual.type,
    title: visual.title,
    metric: visual.metric,
    dimension: visual.dimension,
    width: visual.width,
  }))
  return spec
}

function addVisual() {
  const metric = selectedMetrics.value[0]
  visuals.value.push({
    id: `v${Date.now()}`,
    type: 'bar',
    title: '新图表',
    metric,
    dimension: selectedDimensions.value[0] || dimensionOptions.value[0]?.name,
    width: 'half',
  })
}

function removeVisual(index) {
  visuals.value.splice(index, 1)
}

function moveVisual(index, delta) {
  const target = index + delta
  if (target < 0 || target >= visuals.value.length) return
  const [item] = visuals.value.splice(index, 1)
  visuals.value.splice(target, 0, item)
}

function clearResults() {
  results.value = []
  disposeCharts()
}

function disposeCharts() {
  for (const chart of charts.values()) chart.dispose()
  charts.clear()
}

function setChartEl(id, el) {
  if (!el) charts.delete(id)
}

function renderAll() {
  disposeCharts()
  for (const visual of results.value) {
    if (!['line', 'bar', 'pie', 'scatter'].includes(visual.type)) continue
    const element = document.querySelector(`[data-visual="${visual.id}"]`)
    if (!element || !visual.rows?.length) continue
    const chart = echarts.init(element)
    charts.set(visual.id, chart)
    renderChart(chart, visual)
  }
}

function renderChart(chart, visual) {
  const columns = visual.columns || []
  const rows = visual.rows || []
  const metricIndex = columns.findIndex((column) => column.key?.startsWith('m'))
  const dimensionIndex = columns.findIndex((column) => column.key?.startsWith('d'))

  if (visual.type === 'scatter') {
    chart.setOption({
      grid: { left: 56, right: 24, top: 24, bottom: 44 },
      tooltip: {
        formatter: (params) => `${visual.dimension}: ${formatNumber(params.value[0])}<br/>`
          + `${visual.metric}: ${formatNumber(params.value[1])}`,
      },
      xAxis: {
        type: 'value',
        name: visual.dimension,
        nameLocation: 'middle',
        nameGap: 26,
        nameTextStyle: { fontSize: 11, color: '#7a8a80' },
        axisLabel: { fontSize: 10 },
        splitLine: { lineStyle: { color: '#eef4f0' } },
      },
      yAxis: {
        type: 'value',
        name: visual.metric,
        nameTextStyle: { fontSize: 11, color: '#7a8a80' },
        axisLabel: { fontSize: 10 },
        splitLine: { lineStyle: { color: '#eef4f0' } },
      },
      series: [{
        type: 'scatter',
        symbolSize: 6,
        itemStyle: { color: 'rgba(79,143,120,0.55)' },
        data: rows.map((row) => [Number(row[0]), Number(row[1])]),
      }],
    }, true)
    return
  }

  if (visual.type === 'pie') {
    chart.setOption({
      tooltip: { trigger: 'item' },
      legend: { type: 'scroll', bottom: 0, textStyle: { fontSize: 10 } },
      series: [{
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['50%', '44%'],
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        label: { fontSize: 10, formatter: '{b}\n{d}%' },
        data: rows.map((row) => ({
          name: String(row[dimensionIndex] ?? '—'),
          value: Number(row[metricIndex] ?? 0),
        })),
      }],
    }, true)
    return
  }

  if (visual.type === 'line') {
    const categories = rows.map((row) => String(row[dimensionIndex] ?? ''))
    const series = columns
      .map((column, index) => ({ column, index }))
      .filter((item) => item.column.key?.startsWith('m'))
      .slice(0, 2)
      .map(({ column, index }) => ({
        name: column.label,
        type: 'line',
        smooth: true,
        symbolSize: 5,
        areaStyle: { opacity: 0.12 },
        data: rows.map((row) => Number(row[index])),
      }))
    chart.setOption({
      grid: { left: 48, right: 20, top: 34, bottom: 30 },
      tooltip: { trigger: 'axis' },
      legend: { top: 4, right: 8, textStyle: { fontSize: 11 } },
      xAxis: { type: 'category', data: categories, axisLabel: { fontSize: 10 } },
      yAxis: { type: 'value', axisLabel: { fontSize: 10 }, splitLine: { lineStyle: { color: '#eef4f0' } } },
      series,
    }, true)
    return
  }

  if (visual.type === 'bar') {
    const labels = rows.map((row) => String(row[dimensionIndex] ?? ''))
    const values = rows.map((row) => Number(row[metricIndex] ?? 0))
    chart.setOption({
      grid: { left: 92, right: 28, top: 16, bottom: 24 },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      xAxis: { type: 'value', axisLabel: { fontSize: 10 }, splitLine: { lineStyle: { color: '#eef4f0' } } },
      yAxis: {
        type: 'category',
        data: labels,
        inverse: true,
        axisLabel: { fontSize: 10, width: 84, overflow: 'truncate' },
      },
      series: [{
        type: 'bar',
        barMaxWidth: 14,
        itemStyle: { borderRadius: [0, 4, 4, 0], color: '#4f8f78' },
        data: values,
      }],
    }, true)
  }
}

async function run() {
  errorMessage.value = ''
  running.value = true
  try {
    const spec = baseSpec()
    const body = await executeAnalytics({
      datasetVersionId: datasetId.value,
      tableId: tableId.value,
      spec,
    })
    results.value = body.visuals || []
    await nextTick()
    renderAll()
    const failed = results.value.filter((item) => item.error)
    if (failed.length) {
      ElMessage.warning(`${failed.length} 个图表失败：${failed[0].error}`)
    } else {
      ElMessage.success('分析完成')
    }
  } catch (error) {
    errorMessage.value = error.message
    ElMessage.error(error.message)
  } finally {
    running.value = false
  }
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
  visuals.value = defaultVisuals()
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
  visuals.value = (spec.visuals || []).length ? spec.visuals.map((item) => ({ ...item })) : defaultVisuals()
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
  if (!results.value.length) {
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

function exportCsv(visual) {
  const columns = visual.columns || []
  const header = columns.map((column) => column.label).join(',')
  const lines = (visual.rows || []).map((row) => columns.map((column, index) => {
    const text = row[index] == null ? '' : formatCell(column, row[index])
    return /[",\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text
  }).join(','))
  const blob = new Blob([`\ufeff${[header, ...lines].join('\n')}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${visual.title || '分析结果'}.csv`
  anchor.click()
  URL.revokeObjectURL(url)
}

function resizeCharts() {
  for (const chart of charts.values()) chart.resize()
}

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
  disposeCharts()
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
          <el-select v-model="datasetId" placeholder="选择数据集" style="width: 240px">
            <el-option v-for="item in datasets" :key="item.datasetId" :label="item.name" :value="item.datasetId" />
          </el-select>
          <el-select v-model="tableId" placeholder="选择数据表" style="width: 180px">
            <el-option v-for="item in catalog?.tables || []" :key="item.tableId" :label="item.tableName" :value="item.tableId" />
          </el-select>
        </div>
        <div class="actions">
          <el-input v-model="planName" placeholder="方案名称" style="width: 160px" />
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
          <el-select v-model="selectedDimensions" multiple :multiple-limit="3" collapse-tags placeholder="按维度分组" style="width: 100%">
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
            <el-select v-model="filter.values" multiple collapse-tags filterable placeholder="取值" style="flex: 1" :disabled="!filter.column">
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

      <section class="visual-config">
        <header class="card-header">
          <h3>可视化设置 <small>按分析内容自定义图表</small></h3>
          <div class="visual-actions">
            <el-button size="small" :icon="Plus" @click="addVisual">添加图表</el-button>
            <el-button size="small" :icon="Refresh" text @click="visuals = defaultVisuals()">恢复默认</el-button>
          </div>
        </header>
        <div v-for="(visual, index) in visuals" :key="visual.id" class="visual-row">
          <el-input v-model="visual.title" placeholder="标题" style="width: 150px" />
          <el-select v-model="visual.type" style="width: 140px">
            <el-option v-for="type in visualTypes" :key="type.value" :label="type.label" :value="type.value" />
          </el-select>

          <template v-if="visual.type === 'scatter'">
            <el-select v-model="visual.dimension" placeholder="X 字段" filterable style="width: 190px">
              <el-option v-for="field in fieldOptions" :key="field.name" :label="field.name" :value="field.name" />
            </el-select>
            <el-select v-model="visual.metric" placeholder="Y 字段" filterable style="width: 190px">
              <el-option v-for="field in fieldOptions" :key="field.name" :label="field.name" :value="field.name" />
            </el-select>
          </template>
          <template v-else-if="['line', 'bar', 'pie'].includes(visual.type)">
            <el-select v-model="visual.metric" placeholder="指标" style="width: 150px">
              <el-option v-for="metric in availableMetrics" :key="metric.key" :label="metric.name" :value="metric.key" />
            </el-select>
            <el-select v-if="visual.type !== 'line'" v-model="visual.dimension" placeholder="维度" clearable style="width: 170px">
              <el-option label="时间维度" value="__time__" />
              <el-option v-for="column in dimensionOptions" :key="column.name" :label="column.name" :value="column.name" />
            </el-select>
          </template>

          <el-select v-model="visual.width" style="width: 90px">
            <el-option label="半宽" value="half" />
            <el-option label="整行" value="full" />
          </el-select>

          <div class="visual-buttons">
            <el-button size="small" text :disabled="index === 0" @click="moveVisual(index, -1)">↑</el-button>
            <el-button size="small" text :disabled="index === visuals.length - 1" @click="moveVisual(index, 1)">↓</el-button>
            <el-button size="small" text :icon="Delete" @click="removeVisual(index)" />
          </div>
        </div>
      </section>

      <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" />

      <section v-if="results.length" class="visual-grid">
        <article
          v-for="visual in results"
          :key="visual.id"
          class="card"
          :class="{ full: visual.width === 'full', half: visual.width !== 'full' }"
        >
          <header class="card-header">
            <h3>{{ visual.title || visual.id }}</h3>
            <div class="result-actions">
              <el-button v-if="visual.type === 'table'" size="small" :icon="DataAnalysis" @click="exportCsv(visual)">CSV</el-button>
              <el-button size="small" :loading="reporting === 'pdf'" @click="exportReport('pdf')">PDF</el-button>
              <el-button size="small" :loading="reporting === 'docx'" @click="exportReport('docx')">Word</el-button>
              <el-button size="small" :loading="reporting === 'html'" @click="exportReport('html')">HTML</el-button>
            </div>
          </header>

          <el-alert v-if="visual.error" :title="visual.error" type="warning" :closable="false" show-icon />

          <template v-else>
            <div v-if="visual.type === 'kpi'" class="kpis">
              <div v-for="(column, index) in visual.columns" :key="column.key" class="kpi">
                <span class="kpi-label">{{ column.label }}</span>
                <strong class="kpi-value">{{ formatCell(column, visual.rows?.[0]?.[index]) }}</strong>
              </div>
            </div>

            <div
              v-else-if="visual.type === 'table'"
              class="table-wrap"
            >
              <el-table :data="visual.rows || []" size="small" border max-height="380">
                <el-table-column v-for="(column, index) in visual.columns" :key="column.key" :label="column.label" :min-width="120">
                  <template #default="{ row }">{{ formatCell(column, row[index]) }}</template>
                </el-table-column>
              </el-table>
            </div>

            <template v-else-if="visual.type === 'scatter'">
              <div :data-visual="visual.id" class="chart" />
              <div class="conclusion">
                <strong>结论：</strong>{{ visual.interpretation }}
                <span v-if="visual.r != null" class="conclusion-meta">
                  r = {{ Number(visual.r).toFixed(3) }} · {{ visual.strength }} · n = {{ visual.n }}
                </span>
              </div>
            </template>

            <div v-else :data-visual="visual.id" class="chart" />
          </template>
        </article>
      </section>

      <el-empty v-else-if="!running" description="选择指标和维度后点「运行分析」，图表按上面的可视化设置生成" />
    </main>
  </section>
</template>

<style scoped>
.workbench {
  display: grid;
  grid-template-columns: 240px 1fr;
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

.config,
.visual-config {
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

.visual-config .card-header {
  margin: 0;
}

.visual-actions {
  display: flex;
  gap: 8px;
}

.visual-row {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.visual-buttons {
  display: flex;
  gap: 2px;
  margin-left: auto;
}

.visual-grid {
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

.card.full {
  grid-column: 1 / -1;
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

.result-actions {
  display: flex;
  gap: 6px;
}

.chart {
  width: 100%;
  height: 280px;
}

.kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
}

.kpi {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border-radius: 8px;
  background: linear-gradient(180deg, #fbfdfc, #ffffff);
  border: 1px solid var(--el-border-color-lighter);
}

.kpi-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.kpi-value {
  font-size: 22px;
  color: #28503f;
  font-variant-numeric: tabular-nums;
}

.table-wrap {
  max-height: 400px;
  overflow: auto;
}

.conclusion {
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f4f8f5;
  color: #33433b;
  font-size: 12px;
  line-height: 1.7;
}

.conclusion-meta {
  margin-left: 8px;
  color: #7b8a82;
}

@media (max-width: 1200px) {
  .visual-grid {
    grid-template-columns: 1fr;
  }
}
</style>
