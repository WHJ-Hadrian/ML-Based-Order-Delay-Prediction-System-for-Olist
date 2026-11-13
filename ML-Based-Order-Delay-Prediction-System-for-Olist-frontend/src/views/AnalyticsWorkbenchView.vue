<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DataAnalysis, Delete, Document, FolderOpened, Plus, Refresh, VideoPlay } from '@element-plus/icons-vue'
import { getDatasetCatalog, listDatasets } from '@/api/datasets'
import {
  copyAnalysisTemplate,
  createAnalysisPlan,
  deleteAnalysisPlan,
  getAnalyticsCapabilities,
  listAnalysisPlans,
  listAnalysisTemplates,
  listAnalyticsValues,
  queryAnalytics,
  updateAnalysisPlan,
} from '@/api/analytics'

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

const result = ref(null)
const running = ref(false)
const errorMessage = ref('')

const plans = ref([])
const templates = ref([])
const activePlanId = ref('')
const planName = ref('')
const saving = ref(false)

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

const metricLabels = computed(() => {
  const map = {}
  for (const metric of availableMetrics.value) map[metric.key] = metric.name
  return map
})

const chartRows = computed(() => {
  const value = result.value
  if (!value || !value.columns?.length || !value.rows?.length) return []
  const dimensionIndex = value.columns.findIndex((column) => column.key.startsWith('d'))
  if (dimensionIndex < 0) return []
  const metricIndex = value.columns.findIndex((column) => column.key.startsWith('m'))
  if (metricIndex < 0) return []
  const rows = value.rows.map((row) => ({
    label: String(row[dimensionIndex] ?? '—'),
    value: Number(row[metricIndex] ?? 0),
  }))
  const max = Math.max(...rows.map((item) => item.value), 1)
  return rows.map((item) => ({ ...item, width: Math.max(2, Math.round((item.value / max) * 100)) }))
})

const chartMetricLabel = computed(() => {
  const value = result.value
  if (!value) return ''
  const column = value.columns.find((item) => item.key.startsWith('m'))
  return column ? column.label : ''
})

async function loadDatasets() {
  try {
    const page = await listDatasets({ page: 0, size: 100 })
    datasets.value = page.items || []
    if (!datasetId.value && datasets.value.length) {
      datasetId.value = datasets.value[0].datasetId
    }
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
    if (tables.length) {
      tableId.value = tables[0].tableId
    }
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
  result.value = null
  if (!datasetId.value || !tableId.value) return
  try {
    capabilities.value = await getAnalyticsCapabilities(datasetId.value, tableId.value)
    const available = (capabilities.value.metrics || []).filter((item) => item.available)
    selectedMetrics.value = available.slice(0, 3).map((item) => item.key)
  } catch (error) {
    errorMessage.value = error.message
  }
}

async function loadValues(target) {
  if (!target.column || !datasetId.value || !tableId.value) return
  try {
    const payload = { datasetVersionId: datasetId.value, tableId: tableId.value, column: target.column, limit: 100 }
    const body = await listAnalyticsValues(payload)
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

function buildSpec() {
  if (!datasetId.value || !tableId.value) throw new Error('请先选择数据集和数据表')
  if (!selectedMetrics.value.length) throw new Error('请至少选择一个指标')
  const spec = {
    source: { datasetVersionId: datasetId.value, tableId: tableId.value },
    metrics: selectedMetrics.value.map((key) => ({ ref: key })),
    limit: limit.value,
  }
  if (selectedDimensions.value.length) {
    spec.dimensions = selectedDimensions.value.map((column) => ({ column }))
    spec.orderBy = [{ key: metricLabels.value[selectedMetrics.value[0]] || '订单量', dir: 'desc' }]
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

async function run() {
  errorMessage.value = ''
  running.value = true
  try {
    const spec = buildSpec()
    const payload = { datasetVersionId: datasetId.value, tableId: tableId.value, spec }
    result.value = await queryAnalytics(payload)
    ElMessage.success(`完成：${result.value.rowCount} 行 / ${result.value.elapsedMs} ms`)
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
  result.value = null
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
  if (!datasetId.value || !tableId.value) await loadCapabilities()
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
    const spec = buildSpec()
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
    const plan = await copyAnalysisTemplate(template.key)
    await loadPlans()
    applyPlan(plan)
    ElMessage.success(`已复制模板：${template.name}`)
  } catch (error) {
    ElMessage.error(error.message)
  }
}

function exportCsv() {
  const value = result.value
  if (!value) return
  const header = value.columns.map((column) => column.label).join(',')
  const lines = value.rows.map((row) => row.map((cell) => {
    const text = cell == null ? '' : String(cell)
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
})
</script>

<template>
  <section class="workbench">
    <aside class="plans">
      <header>
        <h3>分析方案</h3>
        <el-button size="small" :icon="Plus" circle @click="resetState" title="新建方案" />
      </header>
      <ul class="plan-list">
        <li v-for="plan in plans" :key="plan.id" :class="{ active: plan.id === activePlanId }">
          <button type="button" class="plan-open" @click="openPlan(plan)">
            <el-icon><Document /></el-icon>
            <span>{{ plan.name }}</span>
            <small>v{{ plan.currentVersion }}</small>
          </button>
          <el-button size="small" text :icon="Delete" @click="removePlan(plan)" title="删除" />
        </li>
        <li v-if="!plans.length" class="empty">还没有保存的方案</li>
      </ul>
      <header class="template-header">
        <h3>系统模板</h3>
        <el-button size="small" text :icon="Refresh" @click="loadTemplates" title="刷新" />
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
          <el-select v-model="datasetId" placeholder="选择数据集" size="default" style="width: 260px">
            <el-option v-for="item in datasets" :key="item.datasetId" :label="item.name" :value="item.datasetId" />
          </el-select>
          <el-select v-model="tableId" placeholder="选择数据表" size="default" style="width: 200px">
            <el-option v-for="item in catalog?.tables || []" :key="item.tableId" :label="item.tableName" :value="item.tableId" />
          </el-select>
        </div>
        <div class="actions">
          <el-input v-model="planName" placeholder="方案名称" size="default" style="width: 180px" />
          <el-button :icon="Refresh" @click="resetState">重置</el-button>
          <el-button :icon="Document" :loading="saving" @click="savePlan">保存方案</el-button>
          <el-button type="primary" :icon="VideoPlay" :loading="running" @click="run">运行</el-button>
        </div>
      </header>

      <div class="controls">
        <section class="control">
          <label>指标</label>
          <el-checkbox-group v-model="selectedMetrics" class="chips">
            <el-checkbox v-for="metric in availableMetrics" :key="metric.key" :value="metric.key">
              {{ metric.name }}
            </el-checkbox>
          </el-checkbox-group>
        </section>

        <section class="control">
          <label>维度（最多 3 个）</label>
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
        </section>

        <section class="control row">
          <div>
            <label>时间维度</label>
            <el-select v-model="timeColumn" clearable placeholder="不使用" style="width: 100%">
              <el-option v-for="column in timeOptions" :key="column.name" :label="column.name" :value="column.name" />
            </el-select>
          </div>
          <div v-if="timeColumn">
            <label>粒度</label>
            <el-select v-model="timeGrain" style="width: 100%">
              <el-option v-for="grain in grainOptions" :key="grain.value" :label="grain.label" :value="grain.value" />
            </el-select>
          </div>
          <div v-if="timeColumn">
            <label>时间范围</label>
            <el-date-picker
              v-model="dateRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              start-placeholder="开始"
              end-placeholder="结束"
              style="width: 100%"
            />
          </div>
        </section>

        <section class="control">
          <label>筛选 <el-button size="small" text :icon="Plus" @click="addFilter">添加</el-button></label>
          <div v-for="(filter, index) in filters" :key="index" class="filter-row">
            <el-select v-model="filter.column" placeholder="字段" style="width: 160px" @change="loadValues(filter)">
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
              <el-option v-for="option in filter.options || []" :key="option.value" :label="`${option.value}（${option.count}）`" :value="option.value" />
            </el-select>
            <el-button v-if="filters.length > 1" text :icon="Delete" @click="removeFilter(index)" />
          </div>
        </section>

        <section class="control row">
          <div>
            <label>返回行数</label>
            <el-select v-model="limit" style="width: 100%">
              <el-option :value="50" label="50" />
              <el-option :value="100" label="100" />
              <el-option :value="200" label="200" />
              <el-option :value="500" label="500" />
              <el-option :value="1000" label="1000" />
            </el-select>
          </div>
        </section>
      </div>

      <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" class="error" />

      <section v-if="result" class="result">
        <header class="result-header">
          <h3>
            结果
            <small>{{ result.rowCount }} 行 · {{ result.elapsedMs }} ms</small>
          </h3>
          <el-button size="small" :icon="DataAnalysis" @click="exportCsv">导出 CSV</el-button>
        </header>

        <div v-if="chartRows.length" class="chart">
          <div class="chart-title">{{ chartMetricLabel }}</div>
          <div v-for="row in chartRows" :key="row.label" class="bar-row">
            <span class="bar-label">{{ row.label }}</span>
            <span class="bar-track"><span class="bar-fill" :style="{ width: `${row.width}%` }" /></span>
            <span class="bar-value">{{ Number(row.value.toFixed(2)) }}</span>
          </div>
        </div>

        <el-table :data="result.rows" size="small" border max-height="420" class="table">
          <el-table-column
            v-for="(column, index) in result.columns"
            :key="column.key"
            :label="column.label"
            :min-width="120"
          >
            <template #default="{ row }">{{ row[index] ?? '—' }}</template>
          </el-table-column>
        </el-table>
      </section>

      <el-empty v-else description="选择指标和维度后点「运行」，结果会显示在这里" />
    </main>
  </section>
</template>

<style scoped>
.workbench {
  display: grid;
  grid-template-columns: 260px 1fr;
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
  font-size: 14px;
  margin: 0;
  color: var(--el-text-color-primary);
}

.plan-list {
  list-style: none;
  margin: 0 0 16px;
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
  padding: 8px;
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
  margin-top: 12px;
  border-top: 1px dashed var(--el-border-color-lighter);
  padding-top: 12px;
}

.board {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
  overflow-y: auto;
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

.controls {
  display: grid;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.control label {
  display: block;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}

.control.row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
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

.error {
  margin: 0;
}

.result {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px;
}

.result-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.result-header h3 {
  margin: 0;
  font-size: 14px;
}

.result-header small {
  color: var(--el-text-color-secondary);
  font-weight: normal;
  margin-left: 8px;
}

.chart {
  display: grid;
  gap: 6px;
  margin-bottom: 12px;
}

.chart-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.bar-row {
  display: grid;
  grid-template-columns: 120px 1fr 80px;
  gap: 8px;
  align-items: center;
  font-size: 12px;
}

.bar-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-regular);
}

.bar-track {
  background: var(--el-fill-color-light);
  border-radius: 4px;
  height: 14px;
  display: block;
}

.bar-fill {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: linear-gradient(90deg, var(--el-color-primary), var(--el-color-primary-light-3));
}

.bar-value {
  text-align: right;
  color: var(--el-text-color-regular);
}
</style>
