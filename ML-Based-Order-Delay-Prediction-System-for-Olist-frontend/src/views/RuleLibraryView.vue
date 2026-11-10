<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowRight,
  Delete,
  EditPen,
  FolderAdd,
  Grid,
  Plus,
  Refresh,
  Search,
} from '@element-plus/icons-vue'
import { AuthenticationRequiredError } from '@/auth/keycloak'
import { getDatasetCatalog, getDatasetVersions, listDatasets } from '@/api/datasets'
import DeriveExpressionEditor from '@/components/DeriveExpressionEditor.vue'
import {
  createRuleWorkbook,
  createRuleRun,
  cancelRuleRun,
  deleteRuleWorkbook,
  getRuleWorkbook,
  getRuleRun,
  getRuleRunExecution,
  listRuleRunNodes,
  listRuleRuns,
  retryRuleRun,
  listRuleWorkbooks,
  listRuleWorkbookVersions,
  publishRuleWorkbook,
  saveRuleWorkbookDraft,
  updateRuleWorkbook,
  validateRuleWorkbook,
} from '@/api/ruleLibrary'

const RULE_SHEET_KEY = 'custom_rules'
const RULE_SHEET_TITLE = '规则明细'
const RULE_SHEET_KIND = 'CUSTOM_RULES'
const LIBRARY_PAGE_SIZE = 5
const RULE_PAGE_SIZE = 8
const AUTOSAVE_DELAY = 1800

const ruleNodeKinds = [
  { id: 'CHECK', label: '数据检查', summary: '验证字段值并设置质量效果' },
  { id: 'TRANSFORM', label: '字段转换', summary: '清理、填充或转换一个字段' },
  { id: 'FILTER', label: '行筛选', summary: '按条件保留符合条件的记录' },
  { id: 'PROJECT', label: '字段选择', summary: '选择需要保留的字段' },
  { id: 'DEDUPE', label: '去重', summary: '按一个或多个字段识别重复行' },
  { id: 'JOIN', label: '数据关联', summary: '用字段键连接两个数据表或上游结果' },
  { id: 'AGGREGATE', label: '分组聚合', summary: '按字段分组并生成统计指标' },
  { id: 'DERIVE', label: '派生字段', summary: '用结构化表达式生成新字段' },
]
const predicateOperators = [
  { id: 'IS_NULL', label: '为空' },
  { id: 'IS_EMPTY', label: '为空字符串' },
  { id: 'EQ', label: '等于' },
  { id: 'NE', label: '不等于' },
  { id: 'GT', label: '大于' },
  { id: 'GTE', label: '大于等于' },
  { id: 'LT', label: '小于' },
  { id: 'LTE', label: '小于等于' },
  { id: 'BETWEEN', label: '介于' },
]
const transformOperations = [
  { id: 'TRIM', label: '去除首尾空格' },
  { id: 'LOWER', label: '转为小写' },
  { id: 'UPPER', label: '转为大写' },
  { id: 'FILL_VALUE', label: '填充缺失值' },
  { id: 'REPLACE', label: '替换文本' },
  { id: 'CAST', label: '转换数据类型' },
]
const transformTypes = [
  { id: 'STRING', label: '文本' },
  { id: 'INT64', label: '整数' },
  { id: 'FLOAT64', label: '小数' },
  { id: 'DATE', label: '日期' },
  { id: 'BOOLEAN', label: '布尔值' },
]
const deriveTypes = [
  { id: 'STRING', label: '文本' },
  { id: 'INTEGER', label: '整数' },
  { id: 'DECIMAL', label: '小数' },
  { id: 'BOOLEAN', label: '布尔值' },
  { id: 'DATE', label: '日期' },
  { id: 'DATETIME', label: '日期时间' },
]
const aggregateOperations = [
  { id: 'COUNT', label: '计数' },
  { id: 'COUNT_DISTINCT', label: '去重计数' },
  { id: 'SUM', label: '求和' },
  { id: 'AVG', label: '平均值' },
  { id: 'MIN', label: '最小值' },
  { id: 'MAX', label: '最大值' },
  { id: 'MEDIAN', label: '中位数' },
]
const joinTypes = [
  { id: 'INNER', label: '仅保留匹配记录' },
  { id: 'LEFT', label: '保留全部左侧记录' },
  { id: 'RIGHT', label: '保留全部右侧记录' },
  { id: 'FULL', label: '保留两侧全部记录' },
]
const joinCardinalities = [
  { id: '1:1', label: '一对一（1:1）' },
  { id: '1:N', label: '一对多（1:N）' },
  { id: 'N:1', label: '多对一（N:1）' },
  { id: 'N:N', label: '多对多（N:N）' },
]

const datasets = ref([])
const loadingDatasets = ref(false)
const datasetCatalog = ref(null)
const loadingCatalog = ref(false)
const catalogRequestId = ref(0)
const createDialogOpen = ref(false)
const createDatasetId = ref('')
const createDatasetVersionId = ref('')
const createDatasetVersions = ref([])
const loadingCreateVersions = ref(false)
const createVersionRequestId = ref(0)
const createDialogError = ref('')
const workbooks = ref([])
const loadingWorkbooks = ref(false)
const creatingLibrary = ref(false)
const libraryKeyword = ref('')
const libraryPage = ref(0)
const libraryPageSize = ref(LIBRARY_PAGE_SIZE)
const ruleCounts = reactive({})
const countCache = new Map()
let countToken = 0

const editingLibraryId = ref('')
const libraryNameDraft = ref('')
const libraryNameInputs = ref({})
let cancelLibraryNameOnBlur = false

const activeWorkbookId = ref('')
const activeWorkbook = ref(null)
const activeRuleSheet = ref(null)
const loadingEditor = ref(false)
const saving = ref(false)
const dirty = ref(false)
const saveTimer = ref(null)
const lastSavedAt = ref('')
const migratingNotice = ref(0)
const changeVersion = ref(0)
const validationResult = ref(null)
const validating = ref(false)
const publishing = ref(false)
const publishedVersion = ref(null)
const previewDrawerOpen = ref(false)
const previewStarting = ref(false)
const previewCancelling = ref(false)
const previewRetrying = ref(false)
const previewSampleSize = ref(100)
const previewRun = ref(null)
const previewNodes = ref([])
const previewHistory = ref([])
const previewExecution = ref(null)
const previewSelectedNodeId = ref('')
const loadingPreview = ref(false)
let previewPollTimer = null
let previewPollToken = 0

const ruleSearch = reactive({})
const rulePages = reactive({})
const ruleNameInputs = ref({})

let suspendDirtyWatch = false

const pagedLibraries = computed(() => {
  const start = libraryPage.value * libraryPageSize.value
  return workbooks.value.slice(start, start + libraryPageSize.value)
})

const showLibraryPagination = computed(() => workbooks.value.length > 0)

const activeDatasetName = computed(() => {
  const id = activeWorkbook.value?.datasetVersionId
  return datasets.value.find((dataset) => dataset.id === id)?.name || '已关联数据集'
})
const activeTables = computed(() => datasetCatalog.value?.tables || [])
const createDatasetOptions = computed(() => datasets.value.filter((dataset) => dataset.status === 'READY'))

const activeRuleSearchState = computed(() => {
  const id = activeWorkbook.value?.id
  return id ? ruleSearch[id] || { input: '', applied: '' } : { input: '', applied: '' }
})

const activeRules = computed(() => {
  const rows = activeRuleSheet.value?.rows || []
  const query = (activeRuleSearchState.value.applied || '').trim().toLowerCase()
  if (!query) {
    return rows
  }
  return rows.filter((rule) => {
    const cells = rule.cells || {}
    return [cells.name, cells.legacyDefinition, nodeKindLabel(cells.nodeKind)]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(query))
  })
})

const activeRuleTotal = computed(() => activeRuleSheet.value?.rows?.length || 0)
const activeRulePage = computed(() => rulePageFor(activeWorkbook.value?.id, activeRules.value.length))
const pagedActiveRules = computed(() => {
  const start = activeRulePage.value * RULE_PAGE_SIZE
  return activeRules.value.slice(start, start + RULE_PAGE_SIZE)
})

const activeSearchQuery = computed(() => (activeRuleSearchState.value.applied || '').trim())

const previewDisplayNodes = computed(() => (previewExecution.value?.compiledPlan?.nodes || [])
  .filter((node) => node.enabled !== false)
  .map((node) => previewNodes.value.find((item) => item.nodeId === node.id) || {
    nodeId: node.id,
    nodeKind: node.kind,
    status: 'PENDING',
    metrics: null,
  }))
const previewCurrentNode = computed(() => previewDisplayNodes.value.find(
  (node) => node.nodeId === previewSelectedNodeId.value,
) || null)
const previewCurrentMetrics = computed(() => previewCurrentNode.value?.metrics || {})
const previewSampleRows = computed(() => Array.isArray(previewCurrentMetrics.value.sample)
  ? previewCurrentMetrics.value.sample
  : [])
const previewSampleColumns = computed(() => {
  const columns = previewCurrentMetrics.value.columns
  if (Array.isArray(columns) && columns.length) return columns
  return Object.keys(previewSampleRows.value[0] || {}).map((name) => ({ name }))
})
const previewMetricCards = computed(() => {
  const metrics = previewCurrentMetrics.value
  return [
    ['输入行', metrics.inputRows],
    ['输出行', metrics.outputRows],
    ['请求样本', metrics.requestedSampleRows],
    ['匹配率', Number.isFinite(metrics.matchedRate) ? `${(metrics.matchedRate * 100).toFixed(1)}%` : null],
  ].filter(([, value]) => value != null)
})

watch(
  () => activeWorkbook.value?.document,
  () => {
    if (suspendDirtyWatch || !activeWorkbook.value) {
      return
    }
    validationResult.value = null
    publishedVersion.value = null
    dirty.value = true
    changeVersion.value += 1
    scheduleAutoSave()
  },
  { deep: true },
)

function handleAuthError(error) {
  return error instanceof AuthenticationRequiredError
}

function formatTime(value) {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function nodeKindLabel(id) {
  return ruleNodeKinds.find((kind) => kind.id === id)?.label || (id === 'LEGACY_NOTE' ? '旧版记录' : '数据检查')
}

function defaultConfig(kind) {
  const field = { type: 'FIELD', nameSnapshot: '' }
  const condition = { op: 'IS_NULL', field, args: [field] }
  switch (kind) {
    case 'CHECK':
      return { schemaVersion: 1, scope: 'ROW', predicate: { op: 'AND', predicates: [condition] }, effect: 'WARN', outputAlias: 'checked' }
    case 'TRANSFORM':
      return { schemaVersion: 1, operation: 'TRIM', target: null, params: {}, outputAlias: 'transformed' }
    case 'FILTER':
      return { schemaVersion: 1, predicate: { op: 'AND', predicates: [condition] }, outputAlias: 'filtered' }
    case 'PROJECT':
      return { schemaVersion: 1, select: [], drop: [], outputAlias: 'projected' }
    case 'DEDUPE':
      return { schemaVersion: 1, keys: [], keep: 'FIRST', outputAlias: 'deduplicated' }
    case 'JOIN':
      return {
        schemaVersion: 1,
        leftAlias: 'left',
        rightAlias: 'right',
        leftKeys: [],
        rightKeys: [],
        joinType: 'INNER',
        cardinality: 'N:1',
        duplicatePolicy: 'FAIL',
        guards: {
          rightKeyUnique: true,
          maxFanout: 1,
          minMatchRate: 0.8,
          onViolation: 'FAIL_RUN',
        },
        outputAlias: 'joined',
      }
    case 'AGGREGATE':
      return {
        schemaVersion: 1,
        groupBy: [],
        metrics: [{ name: 'record_count', op: 'COUNT', field: '*' }],
        outputAlias: 'aggregated',
      }
    case 'DERIVE':
      return {
        schemaVersion: 1,
        outputField: '',
        dataType: 'STRING',
        expression: { type: 'FIELD', nameSnapshot: '' },
        replaceExisting: false,
        outputAlias: 'derived',
      }
    default:
      return { schemaVersion: 1, scope: 'ROW', predicate: { op: 'AND', predicates: [condition] }, effect: 'WARN' }
  }
}

function ruleInput(rule) {
  return rule.cells?.inputs?.[0] || null
}

function inputValue(input) {
  if (input?.type === 'TABLE') return `table:${input.tableId}`
  if (input?.type === 'NODE_OUTPUT') return `node:${input.nodeId}`
  return ''
}

function joinInput(rule, side) {
  const alias = rule.cells?.config?.[`${side}Alias`] || side
  return rule.cells?.inputs?.find((input) => input.alias === alias) || null
}

function inputTable(input) {
  const tableId = input?.type === 'TABLE' ? input.tableId : ''
  return activeTables.value.find((table) => table.tableId === tableId) || null
}

function inputColumns(input, visited = new Set()) {
  if (input?.type === 'TABLE') {
    return (inputTable(input)?.columns || []).map((column) => ({
      ...column,
      sourceTableId: input.tableId,
      sourceAlias: input.alias,
    }))
  }
  if (input?.type === 'NODE_OUTPUT') {
    const upstream = upstreamRule(input)
    return upstream
      ? ruleOutputColumns(upstream, visited).map((column) => ({
        ...column,
        sourceSide: column.sourceSide || column.sourceAlias,
        sourceAlias: input.alias,
      }))
      : []
  }
  return []
}

function ruleTable(rule) {
  return inputTable(ruleInput(rule))
}

function ruleNodeId(rule) {
  return rule.cells?.nodeId || `${RULE_SHEET_KEY}:${rule.id}`
}

function ruleOutputAlias(rule) {
  return rule.cells?.config?.outputAlias?.trim() || ''
}

function uniqueOutputAlias(kind, rowId) {
  const suffix = String(rowId || Date.now()).replace(/[^A-Za-z0-9_]/g, '_').slice(-28)
  return `${String(kind || 'node').toLowerCase()}_${suffix}`
}

function replaceFieldReferenceAlias(value, oldAlias, newAlias) {
  if (!value || typeof value !== 'object') return
  if (Array.isArray(value)) {
    value.forEach((item) => replaceFieldReferenceAlias(item, oldAlias, newAlias))
    return
  }
  if (value.tableRef?.alias === oldAlias) value.tableRef.alias = newAlias
  Object.values(value).forEach((item) => replaceFieldReferenceAlias(item, oldAlias, newAlias))
}

function updateOutputAlias(rule, outputAlias) {
  rule.cells.config.outputAlias = outputAlias
  const nodeId = ruleNodeId(rule)
  for (const downstream of activeRuleSheet.value?.rows || []) {
    const input = downstream.cells?.inputs?.find((candidate) => (
      candidate.type === 'NODE_OUTPUT' && candidate.nodeId === nodeId
    ))
    if (input) {
      input.outputAlias = outputAlias
      if (downstream.cells?.nodeKind !== 'JOIN') {
        const previousAlias = input.alias
        input.alias = outputAlias
        if (previousAlias && previousAlias !== outputAlias) {
          replaceFieldReferenceAlias(downstream.cells.config, previousAlias, outputAlias)
        }
      }
    }
  }
}

function upstreamRule(input) {
  if (input?.type !== 'NODE_OUTPUT') return null
  return activeRuleSheet.value?.rows?.find((candidate) => ruleNodeId(candidate) === input.nodeId) || null
}

function outputRulesFor(rule) {
  return (activeRuleSheet.value?.rows || []).filter((candidate) => (
    candidate.id !== rule.id
    && candidate.enabled !== false
    && candidate.cells?.nodeKind !== 'LEGACY_NOTE'
    && ruleOutputAlias(candidate)
  ))
}

function ruleInputValue(rule) {
  return inputValue(ruleInput(rule))
}

function ruleInputAlias(rule) {
  const input = ruleInput(rule)
  return input?.alias || input?.outputAlias || 'input'
}

function ruleColumns(rule, visited = new Set()) {
  const nodeId = ruleNodeId(rule)
  if (visited.has(nodeId)) return []
  const nextVisited = new Set(visited).add(nodeId)
  if (rule.cells?.nodeKind === 'JOIN') {
    return [
      ...inputColumns(joinInput(rule, 'left'), nextVisited).map((column) => ({ ...column, sourceSide: 'left' })),
      ...inputColumns(joinInput(rule, 'right'), nextVisited).map((column) => ({ ...column, sourceSide: 'right' })),
    ]
  }
  return inputColumns(ruleInput(rule), nextVisited)
}

function ruleOutputColumns(rule, visited = new Set()) {
  let columns = ruleColumns(rule, visited)
  if (rule.cells?.nodeKind === 'PROJECT') {
    const selected = new Set(selectedFieldIds(rule.cells.config?.select))
    const dropped = new Set(selectedFieldIds(rule.cells.config?.drop))
    return columns.filter((column) => (
      (!selected.size || selected.has(column.columnId)) && !dropped.has(column.columnId)
    ))
  }
  if (rule.cells?.nodeKind === 'DERIVE') {
    const outputField = rule.cells.config?.outputField?.trim()
    if (!outputField) return columns
    if (rule.cells.config?.replaceExisting) {
      columns = columns.filter((column) => column.name.toLowerCase() !== outputField.toLowerCase())
    }
    return [
      ...columns,
      {
        columnId: `name:${outputField}`,
        name: outputField,
        type: rule.cells.config?.dataType || 'STRING',
        synthetic: true,
      },
    ]
  }
  if (rule.cells?.nodeKind === 'AGGREGATE') {
    const config = rule.cells.config || {}
    const groupIds = new Set(selectedFieldIds(config.groupBy))
    const output = columns.filter((column) => groupIds.has(column.columnId))
    for (const [index, metric] of (config.metrics || []).entries()) {
      const name = String(metric?.name || '').trim()
      if (!name) continue
      const operation = String(metric.op || '').toUpperCase()
      const source = columns.find((column) => fieldColumnId(metric.field) === column.columnId)
      const type = ['COUNT', 'COUNT_DISTINCT'].includes(operation)
        ? 'INTEGER'
        : ['AVG', 'MEDIAN'].includes(operation)
          ? 'DECIMAL'
          : source?.type || 'DECIMAL'
      output.push({ columnId: `name:${name}`, name, type, synthetic: true, metricIndex: index })
    }
    return output
  }
  return columns
}

function joinColumns(rule, side) {
  return inputColumns(joinInput(rule, side), new Set([ruleNodeId(rule)]))
}

function joinFieldRef(rule, side, columnId) {
  const input = joinInput(rule, side)
  const column = joinColumns(rule, side).find((item) => item.columnId === columnId)
  if (!input || !column) return null
  const reference = {
    type: 'FIELD',
    nameSnapshot: column.name,
    tableRef: {
      alias: rule.cells.config[`${side}Alias`] || side,
      ...(column.sourceTableId ? { tableId: column.sourceTableId } : {}),
    },
  }
  if (!column.synthetic) reference.columnId = column.columnId
  return reference
}

function columnOptionLabel(column) {
  return column.sourceSide ? `${column.sourceSide === 'left' ? '左侧' : '右侧'} · ${column.name}` : column.name
}

function parseInputSelection(rule, selected, alias) {
  const value = String(selected || '')
  if (value.startsWith('table:')) {
    const tableId = value.slice(6)
    return activeTables.value.some((table) => table.tableId === tableId)
      ? { type: 'TABLE', tableId, alias }
      : null
  }
  if (value.startsWith('node:')) {
    const nodeId = value.slice(5)
    const upstream = outputRulesFor(rule).find((candidate) => ruleNodeId(candidate) === nodeId)
    const outputAlias = upstream ? ruleOutputAlias(upstream) : ''
    return upstream && outputAlias
      ? { type: 'NODE_OUTPUT', nodeId, outputAlias, alias }
      : null
  }
  return null
}

function setJoinInput(rule, side, selected) {
  const alias = rule.cells.config[`${side}Alias`] || side
  const oppositeAlias = rule.cells.config[`${side === 'left' ? 'right' : 'left'}Alias`]
    || (side === 'left' ? 'right' : 'left')
  const nextInput = parseInputSelection(rule, selected, alias)
  rule.cells.inputs = (rule.cells.inputs || []).filter((input) => input.alias === oppositeAlias)
  if (nextInput) rule.cells.inputs.push(nextInput)
  rule.cells.config.leftKeys = []
  rule.cells.config.rightKeys = []
}

function joinInputValue(rule, side) {
  return inputValue(joinInput(rule, side))
}

function selectedJoinInputTitle(rule, side) {
  const input = joinInput(rule, side)
  if (input?.type === 'TABLE') return inputTable(input)?.tableName || ''
  if (input?.type === 'NODE_OUTPUT') {
    const upstream = upstreamRule(input)
    return upstream ? `${upstream.cells.name || nodeKindLabel(upstream.cells.nodeKind)} · ${ruleOutputAlias(upstream)}` : ''
  }
  return ''
}

function selectedJoinFieldTitle(rule, side, index) {
  const keys = side === 'left' ? rule.cells.config.leftKeys : rule.cells.config.rightKeys
  const id = fieldColumnId(keys?.[index])
  return joinColumns(rule, side).find((column) => column.columnId === id)?.name || ''
}

function joinSelectionDisabled(rule, side, value) {
  return joinInputValue(rule, side === 'left' ? 'right' : 'left') === value
}

function joinKeyRows(rule) {
  return Math.max(rule.cells.config.leftKeys?.length || 0, rule.cells.config.rightKeys?.length || 0)
}

function addJoinKey(rule) {
  const config = rule.cells.config
  if (!Array.isArray(config.leftKeys)) config.leftKeys = []
  if (!Array.isArray(config.rightKeys)) config.rightKeys = []
  config.leftKeys.push(null)
  config.rightKeys.push(null)
}

function updateJoinKey(rule, side, index, columnId) {
  const key = side === 'left' ? 'leftKeys' : 'rightKeys'
  rule.cells.config[key][index] = joinFieldRef(rule, side, columnId)
}

function removeJoinKey(rule, index) {
  rule.cells.config.leftKeys.splice(index, 1)
  rule.cells.config.rightKeys.splice(index, 1)
}

function aggregateMetricFieldValue(metric) {
  return metric?.field === '*' ? '*' : fieldColumnId(metric?.field)
}

function updateAggregateMetricField(rule, metric, value) {
  metric.field = value === '*' && metric.op === 'COUNT'
    ? '*'
    : fieldRef(rule, value)
}

function updateAggregateMetricOperation(rule, metric, operation) {
  metric.op = operation
  if (operation !== 'COUNT' && metric.field === '*') {
    metric.field = fieldRef(rule, ruleColumns(rule)[0]?.columnId)
  } else if (operation === 'COUNT' && !metric.field) {
    metric.field = '*'
  }
}

function addAggregateMetric(rule) {
  const metrics = rule.cells.config.metrics || (rule.cells.config.metrics = [])
  metrics.push({
    name: `metric_${metrics.length + 1}`,
    op: 'COUNT',
    field: '*',
  })
}

function removeAggregateMetric(rule, index) {
  const metrics = rule.cells.config.metrics || []
  if (metrics.length > 1) metrics.splice(index, 1)
}

function previewRunStatusLabel(status) {
  return ({ QUEUED: '排队中', RUNNING: '运行中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消' })[status] || status || '未知'
}

function previewNodeStatusLabel(status) {
  return ({ PENDING: '等待中', RUNNING: '运行中', SUCCEEDED: '完成', FAILED: '失败', SKIPPED: '跳过', CANCELLED: '已取消' })[status] || status || '等待中'
}

function previewRunOption(run) {
  const time = run.createdAt ? formatTime(run.createdAt) : '时间未知'
  const attempt = run.executionAttempt > 0 ? ` · 尝试 ${run.executionAttempt}` : ''
  return `${time} · ${previewRunStatusLabel(run.status)}${attempt}`
}

function previewRunLeaseActive(run) {
  if (!run?.leaseExpiresAt) return false
  const expiresAt = new Date(run.leaseExpiresAt).getTime()
  return Number.isFinite(expiresAt) && expiresAt > Date.now()
}

function previewRunSummary(run) {
  if (!run) return ''
  if (run.status === 'CANCELLED') return '运行已取消；正在执行的只读查询会在当前节点结束后停止上报。'
  if (run.status === 'QUEUED') {
    if (!run.claimedBy) return '运行已进入队列；等待可用 Worker 领取。'
    if (!previewRunLeaseActive(run)) return '上一次 Worker 租约已过期；运行会被安全重领并从头执行。'
    return 'Worker 已领取运行，正在准备只读预览。'
  }
  if (run.status === 'RUNNING' && run.claimedBy && !previewRunLeaseActive(run)) {
    return 'Worker 租约已过期；系统正在等待可用 Worker 安全重领。'
  }
  return `样本 ${run.sampleSize ?? '-'} 行 · 随机种子 ${run.seed ?? '-'}`
}

function previewRunLeaseLabel(run) {
  if (!run?.leaseExpiresAt) return ''
  return `${previewRunLeaseActive(run) ? '租约有效至' : '租约已过期'} ${formatTime(run.leaseExpiresAt)}`
}

function previewNodeName(node) {
  const planNode = previewExecution.value?.compiledPlan?.nodes?.find((item) => item.id === node.nodeId)
  const rule = activeRuleSheet.value?.rows?.find((item) => ruleNodeId(item) === node.nodeId)
  return rule?.cells?.name || planNode?.name || node.nodeId
}

function stopPreviewPolling() {
  previewPollToken += 1
  if (previewPollTimer) {
    window.clearTimeout(previewPollTimer)
    previewPollTimer = null
  }
}

function schedulePreviewPoll(runId, token) {
  if (token !== previewPollToken) return
  previewPollTimer = window.setTimeout(() => refreshPreviewRun(runId, token), 1600)
}

async function refreshPreviewRun(runId, token) {
  try {
    const [run, nodes, execution] = await Promise.all([
      getRuleRun(runId),
      listRuleRunNodes(runId),
      getRuleRunExecution(runId),
    ])
    if (token !== previewPollToken) return
    previewRun.value = run
    previewNodes.value = nodes || []
    previewExecution.value = execution
    const defaultNodeId = [...(execution?.compiledPlan?.nodes || [])]
      .reverse()
      .find((node) => node.enabled !== false)?.id || previewNodes.value.at(-1)?.nodeId || ''
    if (!previewNodes.value.some((node) => node.nodeId === previewSelectedNodeId.value)) {
      previewSelectedNodeId.value = defaultNodeId
    }
    if (['QUEUED', 'RUNNING'].includes(run.status)) schedulePreviewPoll(runId, token)
    else previewHistory.value = await listRuleRuns(run.workbookId, 20)
  } catch (error) {
    if (token === previewPollToken && !handleAuthError(error)) {
      ElMessage.error(error?.message || '读取预览运行状态失败')
    }
  }
}

async function selectPreviewRun(runId) {
  if (!runId) return
  stopPreviewPolling()
  const token = previewPollToken
  loadingPreview.value = true
  try {
    const [run, nodes, execution] = await Promise.all([
      getRuleRun(runId),
      listRuleRunNodes(runId),
      getRuleRunExecution(runId),
    ])
    if (token !== previewPollToken) return
    previewRun.value = run
    previewNodes.value = nodes || []
    previewExecution.value = execution
    const defaultNodeId = [...(execution?.compiledPlan?.nodes || [])]
      .reverse()
      .find((node) => node.enabled !== false)?.id || previewNodes.value.at(-1)?.nodeId || ''
    previewSelectedNodeId.value = defaultNodeId
    if (['QUEUED', 'RUNNING'].includes(run.status)) schedulePreviewPoll(run.id, token)
  } catch (error) {
    if (!handleAuthError(error)) ElMessage.error(error?.message || '加载预览记录失败')
  } finally {
    if (token === previewPollToken) loadingPreview.value = false
  }
}

async function cancelPreviewRun() {
  const run = previewRun.value
  if (!run?.id || !['QUEUED', 'RUNNING'].includes(run.status) || previewCancelling.value) return
  previewCancelling.value = true
  try {
    await cancelRuleRun(run.id)
    await selectPreviewRun(run.id)
    previewHistory.value = await listRuleRuns(run.workbookId, 20)
    ElMessage.success('运行已取消；正在执行的只读查询会在当前节点结束后停止上报')
  } catch (error) {
    if (!handleAuthError(error)) ElMessage.error(error?.message || '取消运行失败')
    if (run?.id) await selectPreviewRun(run.id)
  } finally {
    previewCancelling.value = false
  }
}

async function retryPreviewRun() {
  const run = previewRun.value
  if (!run?.id || !['FAILED', 'CANCELLED'].includes(run.status) || previewRetrying.value) return
  previewRetrying.value = true
  try {
    const retry = await retryRuleRun(run.id)
    previewHistory.value = await listRuleRuns(run.workbookId, 20)
    await selectPreviewRun(retry.id)
    ElMessage.success('已按原规则版本和数据集版本创建新的预览运行')
  } catch (error) {
    if (!handleAuthError(error)) ElMessage.error(error?.message || '创建安全重试失败')
  } finally {
    previewRetrying.value = false
  }
}

async function startRulePreview() {
  if (!activeWorkbook.value || previewStarting.value) return
  previewStarting.value = true
  const workbook = activeWorkbook.value
  const reusePublishedVersion = !dirty.value && workbook.status === 'PUBLISHED'
  try {
    if (dirty.value && !(await persistDraft({ silent: true }))) return
    if (!(await validateWorkbook())) return
    const unsupportedKinds = [...new Set((validationResult.value?.compiledPlan?.nodes || [])
      .filter((node) => node.enabled !== false && !['CHECK', 'TRANSFORM', 'FILTER', 'PROJECT', 'DEDUPE', 'JOIN', 'AGGREGATE', 'DERIVE'].includes(node.kind))
      .map((node) => node.kind))]
    if (unsupportedKinds.length) {
      ElMessage.warning(`当前预览执行器尚未支持：${unsupportedKinds.join('、')}`)
      return
    }

    let version
    if (reusePublishedVersion) {
      const versions = await listRuleWorkbookVersions(workbook.id)
      version = [...(versions || [])].sort((left, right) => right.versionNumber - left.versionNumber)[0]
    }
    if (!version) {
      version = await publishRuleWorkbook(workbook.id)
      publishedVersion.value = version
      workbook.status = 'PUBLISHED'
      const item = workbooks.value.find((library) => library.id === workbook.id)
      if (item) item.status = 'PUBLISHED'
    }
    const run = await createRuleRun(workbook.id, {
      workbookVersionId: version.id,
      mode: 'PREVIEW',
      sampleSize: previewSampleSize.value,
    })
    previewDrawerOpen.value = true
    previewHistory.value = await listRuleRuns(workbook.id, 20)
    await selectPreviewRun(run.id)
  } catch (error) {
    if (!handleAuthError(error)) ElMessage.error(error?.message || '创建预览运行失败')
  } finally {
    previewStarting.value = false
  }
}

async function loadPreviewHistory() {
  if (!activeWorkbook.value) return
  try {
    previewHistory.value = await listRuleRuns(activeWorkbook.value.id, 20)
    const selected = previewHistory.value.find((run) => run.id === previewRun.value?.id)
      || previewHistory.value[0]
    if (selected) await selectPreviewRun(selected.id)
  } catch (error) {
    if (!handleAuthError(error)) ElMessage.error(error?.message || '加载运行历史失败')
  }
}

async function handlePreviewDrawerOpened() {
  if (previewStarting.value) return
  if (previewRun.value?.id) {
    await selectPreviewRun(previewRun.value.id)
  } else {
    await loadPreviewHistory()
  }
}

function openPreviewResults() {
  previewDrawerOpen.value = true
}

function fieldRef(rule, columnId) {
  const column = ruleColumns(rule).find((item) => item.columnId === columnId)
  const input = ruleInput(rule)
  if (!column || !input) return null
  const reference = {
    type: 'FIELD',
    nameSnapshot: column.name,
    tableRef: {
      alias: input.alias || input.outputAlias || 'input',
      ...(column.sourceTableId ? { tableId: column.sourceTableId } : {}),
    },
  }
  if (!column.synthetic) reference.columnId = column.columnId
  return reference
}

function fieldColumnId(reference) {
  return reference?.columnId || (reference?.nameSnapshot ? `name:${reference.nameSnapshot}` : '')
}

function setRuleInput(rule, value) {
  const selected = String(value || '')
  const type = selected.startsWith('node:') ? 'node' : selected.startsWith('table:') ? 'table' : ''
  const id = type ? selected.slice(type.length + 1) : ''
  if (type === 'table') {
    const table = activeTables.value.find((item) => item.tableId === id)
    if (!table) {
      rule.cells.inputs = []
      rule.cells.config = defaultConfig(rule.cells.nodeKind)
      updateOutputAlias(rule, uniqueOutputAlias(rule.cells.nodeKind, rule.id))
      return
    }
    rule.cells.inputs = [{ type: 'TABLE', tableId: id, alias: 'input' }]
  } else if (type === 'node') {
    const upstream = (activeRuleSheet.value?.rows || []).find((candidate) => ruleNodeId(candidate) === id)
    const outputAlias = upstream ? ruleOutputAlias(upstream) : ''
    if (!upstream || !outputAlias) {
      rule.cells.inputs = []
      rule.cells.config = defaultConfig(rule.cells.nodeKind)
      updateOutputAlias(rule, uniqueOutputAlias(rule.cells.nodeKind, rule.id))
      return
    }
    rule.cells.inputs = [{ type: 'NODE_OUTPUT', nodeId: id, outputAlias, alias: outputAlias }]
  } else {
    rule.cells.inputs = []
    rule.cells.config = defaultConfig(rule.cells.nodeKind)
    updateOutputAlias(rule, uniqueOutputAlias(rule.cells.nodeKind, rule.id))
    return
  }
  rule.cells.config = defaultConfig(rule.cells.nodeKind)
  updateOutputAlias(rule, uniqueOutputAlias(rule.cells.nodeKind, rule.id))
}

function ensurePredicate(rule) {
  const config = rule.cells.config
  if (!config.predicate || !['AND', 'OR'].includes(config.predicate.op)) {
    config.predicate = { op: 'AND', predicates: [] }
  }
  if (!Array.isArray(config.predicate.predicates)) config.predicate.predicates = []
  if (!config.predicate.predicates.length) addCondition(rule)
  return config.predicate
}

function conditionsFor(rule) {
  return ensurePredicate(rule).predicates
}

function addCondition(rule) {
  const predicate = rule.cells.config.predicate || { op: 'AND', predicates: [] }
  if (!Array.isArray(predicate.predicates)) predicate.predicates = []
  const field = fieldRef(rule, ruleColumns(rule)[0]?.columnId) || { type: 'FIELD', nameSnapshot: '' }
  predicate.predicates.push({ op: 'IS_NULL', field, args: [field] })
  rule.cells.config.predicate = predicate
}

function removeCondition(rule, index) {
  const conditions = conditionsFor(rule)
  conditions.splice(index, 1)
  if (!conditions.length) addCondition(rule)
}

function updateConditionField(rule, condition, columnId) {
  const selected = fieldRef(rule, columnId)
  condition.field = selected || { type: 'FIELD', nameSnapshot: '' }
  condition.args = selected ? [selected] : []
}

function changeNodeKind(rule, nodeKind) {
  if (rule.cells.nodeKind === nodeKind) return
  const previousKind = rule.cells.nodeKind
  const previousPrimaryInput = previousKind === 'JOIN'
    ? (joinInput(rule, 'left') || joinInput(rule, 'right'))
    : ruleInput(rule)
  rule.cells.nodeKind = nodeKind
  rule.cells.config = defaultConfig(nodeKind)
  if (nodeKind === 'JOIN') {
    rule.cells.inputs = previousPrimaryInput
      ? [{ ...previousPrimaryInput, alias: 'left' }]
      : []
  } else if (previousKind === 'JOIN') {
    rule.cells.inputs = previousPrimaryInput
      ? [{ ...previousPrimaryInput, alias: 'input' }]
      : []
  }
  updateOutputAlias(rule, uniqueOutputAlias(nodeKind, rule.id))
}

function changePredicateOperator(condition, operator) {
  condition.op = operator
  delete condition.value
  delete condition.upperValue
  if (operator === 'IS_NULL' || operator === 'IS_EMPTY') {
    condition.args = condition.field ? [condition.field] : []
  }
}

function selectedFieldIds(references) {
  return Array.isArray(references) ? references.map(fieldColumnId).filter(Boolean) : []
}

function updateFieldList(rule, key, selectedIds) {
  rule.cells.config[key] = selectedIds.map((id) => fieldRef(rule, id)).filter(Boolean)
}

function ensureRuleSearch(libraryId) {
  if (!ruleSearch[libraryId]) {
    ruleSearch[libraryId] = { input: '', applied: '' }
  }
  return ruleSearch[libraryId]
}

async function loadDatasets() {
  loadingDatasets.value = true
  try {
    const result = await listDatasets({ status: 'READY', page: 0, size: 100 })
    datasets.value = result?.items ?? []
  } catch (error) {
    if (!handleAuthError(error)) {
      ElMessage.error(error?.message || '加载数据集失败')
    }
  } finally {
    loadingDatasets.value = false
  }
}

async function loadWorkbooks() {
  loadingWorkbooks.value = true
  try {
    const result = await listRuleWorkbooks({
      page: 0,
      size: 100,
      keyword: libraryKeyword.value,
    })
    workbooks.value = result?.items ?? []
    const maxPage = Math.max(0, Math.ceil(workbooks.value.length / libraryPageSize.value) - 1)
    libraryPage.value = Math.min(libraryPage.value, maxPage)
    await loadVisibleCounts()
  } catch (error) {
    if (!handleAuthError(error)) {
      ElMessage.error(error?.message || '加载规则库失败')
    }
  } finally {
    loadingWorkbooks.value = false
  }
}

async function loadVisibleCounts() {
  const token = ++countToken
  const targets = pagedLibraries.value.filter(
    (library) => countCache.get(library.id) !== library.revision,
  )
  await Promise.all(
    targets.map(async (library) => {
      try {
        const detail = await getRuleWorkbook(library.id)
        if (token !== countToken) {
          return
        }
        ruleCounts[library.id] = ruleCountFromDocument(detail.document)
        countCache.set(library.id, library.revision)
      } catch (error) {
        if (handleAuthError(error)) {
          return
        }
      }
    }),
  )
}
async function searchLibraries() {
  libraryPage.value = 0
  await loadWorkbooks()
  await closeActiveIfHidden()
}

function resetLibrarySearch() {
  libraryKeyword.value = ''
  libraryPage.value = 0
  loadWorkbooks().then(closeActiveIfHidden)
}

async function handleLibraryPageChange(page) {
  libraryPage.value = page - 1
  await loadVisibleCounts()
  await closeActiveIfHidden()
}

async function changeLibrarySize(size) {
  libraryPageSize.value = size
  libraryPage.value = 0
  await loadVisibleCounts()
  await closeActiveIfHidden()
}

async function closeActiveIfHidden() {
  if (!activeWorkbookId.value) {
    return
  }
  if (!pagedLibraries.value.some((library) => library.id === activeWorkbookId.value)) {
    await closeLibrary()
  }
}

function nextDefaultName() {
  const used = new Set(workbooks.value.map((library) => library.name.toLowerCase()))
  const base = '未命名规则库'
  if (!used.has(base.toLowerCase())) {
    return base
  }
  let index = 2
  while (used.has(`${base} ${index}`.toLowerCase())) {
    index += 1
  }
  return `${base} ${index}`
}

async function loadDatasetCatalog(datasetVersionId) {
  const requestId = ++catalogRequestId.value
  datasetCatalog.value = null
  if (!datasetVersionId) {
    loadingCatalog.value = false
    return
  }
  loadingCatalog.value = true
  try {
    const catalog = await getDatasetCatalog(datasetVersionId)
    if (requestId === catalogRequestId.value) datasetCatalog.value = catalog
  } catch (error) {
    if (requestId === catalogRequestId.value && !handleAuthError(error)) {
      ElMessage.error(error?.message || '读取数据表和字段失败')
    }
  } finally {
    if (requestId === catalogRequestId.value) loadingCatalog.value = false
  }
}

function buildRuleColumns() {
  return [
    {
      key: 'name',
      title: '规则名称',
      cellType: 'TEXT',
      required: false,
      system: true,
      hidden: false,
      width: 240,
    },
    {
      key: 'nodeKind',
      title: '节点类型',
      cellType: 'NODE_KIND',
      required: false,
      system: true,
      hidden: false,
      width: 180,
    },
    {
      key: 'inputs',
      title: '数据输入',
      cellType: 'INPUT_REF',
      required: false,
      system: true,
      hidden: false,
      width: 220,
    },
    {
      key: 'config',
      title: '节点配置',
      cellType: 'PARAM_OBJECT',
      required: false,
      system: true,
      hidden: false,
      width: 360,
    },
  ]
}

function createRuleSheet(rows = []) {
  return {
    key: RULE_SHEET_KEY,
    title: RULE_SHEET_TITLE,
    kind: RULE_SHEET_KIND,
    order: 999,
    columns: buildRuleColumns(),
    rows: rows.map((row) => normalizeRuleRow(row)),
  }
}

function buildEmptyDocument() {
  return {
    schemaVersion: 1,
    sheets: [createRuleSheet()],
  }
}

function isRuleSheet(sheet) {
  return Boolean(sheet) && (sheet.key === RULE_SHEET_KEY || sheet.kind === RULE_SHEET_KIND)
}

function nextRowId() {
  return `rule_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
}

function normalizeRuleRow(row) {
  if (!row.cells || typeof row.cells !== 'object') {
    row.cells = {}
  }
  if (row.enabled == null) {
    row.enabled = true
  }
  if (!row.id) {
    row.id = nextRowId()
  }
  if (row.cells.name == null) row.cells.name = ''
  if (!row.cells.nodeId) row.cells.nodeId = `${RULE_SHEET_KEY}:${row.id}`
  if (!ruleNodeKinds.some((kind) => kind.id === row.cells.nodeKind)) {
    row.cells.legacyDefinition = row.cells.legacyDefinition ?? row.cells.definition ?? ''
    row.cells.nodeKind = 'LEGACY_NOTE'
    row.enabled = false
  } else if (!row.cells.config || typeof row.cells.config !== 'object') {
    row.cells.config = defaultConfig(row.cells.nodeKind)
  }
  if (row.cells.nodeKind !== 'LEGACY_NOTE' && !row.cells.config.outputAlias) {
    row.cells.config.outputAlias = uniqueOutputAlias(row.cells.nodeKind, row.id)
  }
  if (row.cells.nodeKind === 'JOIN') {
    const defaults = defaultConfig('JOIN')
    if (!Array.isArray(row.cells.config.leftKeys)) row.cells.config.leftKeys = []
    if (!Array.isArray(row.cells.config.rightKeys)) row.cells.config.rightKeys = []
    if (!row.cells.config.leftAlias) row.cells.config.leftAlias = 'left'
    if (!row.cells.config.rightAlias) row.cells.config.rightAlias = 'right'
    if (!row.cells.config.joinType) row.cells.config.joinType = defaults.joinType
    if (!row.cells.config.cardinality) row.cells.config.cardinality = defaults.cardinality
    row.cells.config.duplicatePolicy = 'FAIL'
    row.cells.config.guards = {
      ...defaults.guards,
      ...(row.cells.config.guards && typeof row.cells.config.guards === 'object'
        ? row.cells.config.guards
        : {}),
    }
  }
  if (!row.cells.config.params || typeof row.cells.config.params !== 'object') {
    row.cells.config.params = {}
  }
  if (!Array.isArray(row.cells.inputs)) {
    row.cells.inputs = row.cells.table?.tableId
      ? [{ type: 'TABLE', tableId: row.cells.table.tableId, alias: 'input' }]
      : []
  }
  return row
}

function ensureRuleSheet(document) {
  if (!document || typeof document !== 'object') {
    return null
  }
  if (!Array.isArray(document.sheets)) {
    document.sheets = []
  }
  let sheet = document.sheets.find(isRuleSheet)
  if (!sheet) {
    sheet = createRuleSheet()
    document.sheets.push(sheet)
  }
  if (!Array.isArray(sheet.columns)) {
    sheet.columns = []
  }
  const existingKeys = new Set(sheet.columns.map((column) => column.key))
  for (const column of buildRuleColumns()) {
    if (!existingKeys.has(column.key)) {
      sheet.columns.push(column)
    }
  }
  if (!Array.isArray(sheet.rows)) {
    sheet.rows = []
  }
  sheet.rows.forEach(normalizeRuleRow)
  return sheet
}

function flattenLegacyRules(document) {
  const result = []
  for (const sheet of document?.sheets ?? []) {
    if (isRuleSheet(sheet)) {
      continue
    }
    const rows = Array.isArray(sheet.rows) ? sheet.rows : []
    rows.forEach((row, index) => {
      const cells = row.cells && typeof row.cells === 'object' ? row.cells : {}
      const label = cells.name
        || cells.outputAlias
        || cells.outputField
        || cells.metricRef
        || `第 ${index + 1} 行`
      result.push({
        id: nextRowId(),
        enabled: false,
        cells: {
          name: `${sheet.title || sheet.key || '旧版规则'} · ${label}`,
          nodeKind: 'LEGACY_NOTE',
          legacyDefinition: JSON.stringify(cells, null, 2),
          inputs: [],
          config: {},
        },
      })
    })
  }
  return result
}

function migrateLegacyRules(document) {
  if ((document?.sheets ?? []).some(isRuleSheet)) {
    return 0
  }
  const migrated = flattenLegacyRules(document)
  if (!migrated.length) {
    return 0
  }
  document.sheets.push(createRuleSheet(migrated))
  return migrated.length
}

function ruleCountFromDocument(document) {
  const sheet = (document?.sheets ?? []).find(isRuleSheet)
  if (sheet) {
    return sheet.rows?.length || 0
  }
  return (document?.sheets ?? []).reduce((sum, item) => sum + (item.rows?.length || 0), 0)
}
async function createLibrary() {
  if (creatingLibrary.value) {
    return
  }
  creatingLibrary.value = true
  try {
    if (!datasets.value.length) {
      await loadDatasets()
    }
    const datasetVersionId = createDatasetVersionId.value || createDatasetOptions.value[0]?.id || ''
    if (!datasetVersionId) {
      ElMessage.warning('请先在数据集库创建一个数据集，再新建规则库')
      return
    }
    const created = await createRuleWorkbook({
      name: nextDefaultName(),
      datasetVersionId,
      document: buildEmptyDocument(),
    })
    libraryKeyword.value = ''
    libraryPage.value = 0
    workbooks.value = [created, ...workbooks.value.filter((item) => item.id !== created.id)]
    ruleCounts[created.id] = 0
    countCache.set(created.id, created.revision)
    ensureRuleSearch(created.id)
    await openLibrary(created)
    await startRenameLibrary(created)
    ElMessage.success('已创建规则库，可以直接修改名称')
  } catch (error) {
    if (!handleAuthError(error)) {
      ElMessage.error(error?.message || '创建规则库失败')
    }
  } finally {
    creatingLibrary.value = false
  }
}

function setLibraryNameInput(el, id) {
  if (el) {
    libraryNameInputs.value[id] = el
  } else {
    delete libraryNameInputs.value[id]
  }
}

async function startRenameLibrary(library) {
  editingLibraryId.value = library.id
  libraryNameDraft.value = library.name
  await nextTick()
  const input = libraryNameInputs.value[library.id]
  input?.focus?.()
  input?.select?.()
}

function cancelLibraryEdit() {
  cancelLibraryNameOnBlur = true
  editingLibraryId.value = ''
  nextTick(() => {
    cancelLibraryNameOnBlur = false
  })
}

function handleLibraryNameEnter(event) {
  if (event.isComposing) {
    return
  }
  event.target.blur()
}

async function commitLibraryName(library) {
  if (cancelLibraryNameOnBlur) {
    return
  }
  const name = libraryNameDraft.value.trim()
  const current = workbooks.value.find((item) => item.id === library.id)
  if (!current) {
    editingLibraryId.value = ''
    return
  }
  if (!name || name === current.name) {
    editingLibraryId.value = ''
    return
  }
  const previousName = current.name
  current.name = name
  editingLibraryId.value = ''
  try {
    const updated = await updateRuleWorkbook(library.id, {
      name,
      description: current.description || '',
    })
    current.name = updated.name
    current.description = updated.description
    current.updatedAt = updated.updatedAt
    if (activeWorkbook.value?.id === library.id) {
      suspendDirtyWatch = true
      activeWorkbook.value.name = updated.name
      if (activeWorkbook.value.document) {
        activeWorkbook.value.document.name = updated.name
      }
      await nextTick()
      suspendDirtyWatch = false
    }
    ElMessage.success('名称已更新')
  } catch (error) {
    current.name = previousName
    if (!handleAuthError(error)) {
      ElMessage.error(error?.message || '重命名失败')
    }
  }
}

async function removeLibrary(library) {
  try {
    await ElMessageBox.confirm(
      `删除规则库「${library.name}」及其中的全部规则？`,
      '删除规则库',
      {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  try {
    await deleteRuleWorkbook(library.id)
    if (activeWorkbookId.value === library.id) {
      clearAutoSave()
      resetEditor()
    }
    delete ruleCounts[library.id]
    countCache.delete(library.id)
    await loadWorkbooks()
    ElMessage.success('规则库已删除')
  } catch (error) {
    if (!handleAuthError(error)) {
      ElMessage.error(error?.message || '删除规则库失败')
    }
  }
}

async function toggleLibrary(library) {
  if (activeWorkbookId.value === library.id) {
    await closeLibrary()
    return
  }
  if (!(await flushBeforeSwitch())) {
    return
  }
  await openLibrary(library)
}

async function openLibrary(library) {
  loadingEditor.value = true
  suspendDirtyWatch = true
  try {
    const detail = await getRuleWorkbook(library.id)
    migratingNotice.value = migrateLegacyRules(detail.document)
    const sheet = ensureRuleSheet(detail.document)
    ensureRuleSearch(detail.id)
    activeWorkbookId.value = detail.id
    activeWorkbook.value = detail
    activeRuleSheet.value = sheet
    ruleCounts[detail.id] = sheet?.rows?.length || 0
    countCache.set(detail.id, detail.revision)
    rulePages[detail.id] = 0
    lastSavedAt.value = detail.updatedAt
    dirty.value = false
    await nextTick()
  } catch (error) {
    if (!handleAuthError(error)) {
      ElMessage.error(error?.message || '打开规则库失败')
    }
    resetEditor()
  } finally {
    suspendDirtyWatch = false
    loadingEditor.value = false
  }
}

function resetEditor() {
  stopPreviewPolling()
  activeWorkbookId.value = ''
  activeWorkbook.value = null
  activeRuleSheet.value = null
  lastSavedAt.value = ''
  migratingNotice.value = 0
  dirty.value = false
  datasetCatalog.value = null
  validationResult.value = null
  publishedVersion.value = null
  previewDrawerOpen.value = false
  previewRun.value = null
  previewNodes.value = []
  previewHistory.value = []
  previewExecution.value = null
  previewSelectedNodeId.value = ''
}

async function closeLibrary() {
  if (!(await flushBeforeSwitch())) {
    return
  }
  resetEditor()
}

async function flushBeforeSwitch() {
  clearAutoSave()
  if (!dirty.value) {
    return true
  }
  return (await persistDraft({ silent: true })) !== null
}

function clearAutoSave() {
  if (saveTimer.value) {
    window.clearTimeout(saveTimer.value)
    saveTimer.value = null
  }
}

function scheduleAutoSave() {
  clearAutoSave()
  saveTimer.value = window.setTimeout(() => {
    saveTimer.value = null
    if (dirty.value) {
      persistDraft({ silent: true })
    }
  }, AUTOSAVE_DELAY)
}

let inFlightSave = null

async function persistDraft({ silent = false } = {}) {
  if (!activeWorkbook.value) {
    return null
  }
  if (inFlightSave) {
    const result = await inFlightSave
    if (!activeWorkbook.value) {
      return null
    }
    if (result && dirty.value) {
      return persistDraft({ silent })
    }
    if (result && !silent) {
      ElMessage.success('规则已保存')
    }
    return result
  }
  inFlightSave = runPersist({ silent })
  try {
    return await inFlightSave
  } finally {
    inFlightSave = null
  }
}

async function runPersist({ silent = false } = {}) {
  const workbook = activeWorkbook.value
  if (!workbook) {
    return null
  }
  saving.value = true
  const snapshotVersion = changeVersion.value
  try {
    const saved = await saveRuleWorkbookDraft(workbook.id, workbook.document, workbook.revision)
    if (activeWorkbook.value?.id !== workbook.id) {
      return saved
    }
    suspendDirtyWatch = true
    workbook.revision = saved.revision
    workbook.status = saved.status
    workbook.updatedAt = saved.updatedAt
    workbook.name = saved.name
    await nextTick()
    suspendDirtyWatch = false
    lastSavedAt.value = saved.updatedAt
    const item = workbooks.value.find((library) => library.id === saved.id)
    if (item) {
      item.revision = saved.revision
      item.status = saved.status
      item.updatedAt = saved.updatedAt
      item.name = saved.name
    }
    ruleCounts[saved.id] = activeRuleSheet.value?.rows?.length || 0
    countCache.set(saved.id, saved.revision)
    if (changeVersion.value === snapshotVersion) {
      dirty.value = false
    } else {
      scheduleAutoSave()
    }
    if (!silent) {
      ElMessage.success('规则已保存')
    }
    return saved
  } catch (error) {
    if (!handleAuthError(error)) {
      ElMessage.error(error?.message || '保存规则失败')
    }
    return null
  } finally {
    saving.value = false
  }
}

function rulePageFor(libraryId, total) {
  if (!libraryId) {
    return 0
  }
  const maxPage = Math.max(0, Math.ceil(total / RULE_PAGE_SIZE) - 1)
  const raw = Number(rulePages[libraryId] || 0)
  return Math.min(Math.max(raw, 0), maxPage)
}

function handleRulePageChange(page) {
  if (!activeWorkbook.value) {
    return
  }
  rulePages[activeWorkbook.value.id] = page - 1
}

function applyRuleSearch() {
  if (!activeWorkbook.value) {
    return
  }
  const entry = ensureRuleSearch(activeWorkbook.value.id)
  entry.applied = entry.input.trim()
  rulePages[activeWorkbook.value.id] = 0
}

function resetRuleSearch() {
  if (!activeWorkbook.value) {
    return
  }
  const entry = ensureRuleSearch(activeWorkbook.value.id)
  entry.input = ''
  entry.applied = ''
  rulePages[activeWorkbook.value.id] = 0
}

function setRuleNameInput(el, id) {
  if (el) {
    ruleNameInputs.value[id] = el
  } else {
    delete ruleNameInputs.value[id]
  }
}

async function focusRuleName(id) {
  await nextTick()
  const input = ruleNameInputs.value[id]
  input?.focus?.()
  input?.select?.()
}

function addRule() {
  const sheet = activeRuleSheet.value
  const libraryId = activeWorkbook.value?.id
  if (!sheet || !libraryId) {
    return
  }
  const search = ensureRuleSearch(libraryId)
  search.input = ''
  search.applied = ''
  const row = normalizeRuleRow({
    id: nextRowId(),
    enabled: true,
    cells: {
      name: `未命名规则 ${sheet.rows.length + 1}`,
      nodeKind: 'CHECK',
      inputs: [],
      config: defaultConfig('CHECK'),
    },
  })
  row.cells.config.outputAlias = uniqueOutputAlias(row.cells.nodeKind, row.id)
  sheet.rows.push(row)
  ruleCounts[libraryId] = sheet.rows.length
  rulePages[libraryId] = Math.max(0, Math.ceil(sheet.rows.length / RULE_PAGE_SIZE) - 1)
  focusRuleName(row.id)
}

async function validateWorkbook() {
  if (!activeWorkbook.value || validating.value) return false
  if (dirty.value && !(await persistDraft({ silent: true }))) return false
  validating.value = true
  try {
    validationResult.value = await validateRuleWorkbook(activeWorkbook.value.id)
    if (validationResult.value.valid) {
      ElMessage.success(`校验通过，可生成 ${validationResult.value.nodeCount} 个节点`)
    } else {
      ElMessage.warning(`发现 ${validationResult.value.issues?.length || 0} 个配置问题`)
    }
    return validationResult.value.valid
  } catch (error) {
    if (!handleAuthError(error)) ElMessage.error(error?.message || '规则校验失败')
    return false
  } finally {
    validating.value = false
  }
}

async function publishWorkbook() {
  if (!activeWorkbook.value || publishing.value) return
  if (dirty.value && !(await persistDraft({ silent: true }))) return
  if (!(await validateWorkbook())) return
  publishing.value = true
  try {
    publishedVersion.value = await publishRuleWorkbook(activeWorkbook.value.id)
    activeWorkbook.value.status = 'PUBLISHED'
    const item = workbooks.value.find((library) => library.id === activeWorkbook.value.id)
    if (item) item.status = 'PUBLISHED'
    ElMessage.success(`已发布第 ${publishedVersion.value.versionNumber} 个版本`)
  } catch (error) {
    if (!handleAuthError(error)) ElMessage.error(error?.message || '发布失败，请先修复校验问题')
  } finally {
    publishing.value = false
  }
}

async function openCreateDialog() {
  if (!datasets.value.length && !loadingDatasets.value) {
    await loadDatasets()
  }
  createDialogError.value = ''
  const firstDataset = createDatasetOptions.value[0]
  createDatasetId.value = firstDataset?.datasetId || firstDataset?.id || ''
  await loadCreateDatasetVersions(createDatasetId.value)
  if (!createDatasetOptions.value.length) {
    createDialogError.value = '当前没有 READY 数据集版本，请先在数据集库完成导入。'
  }
  createDialogOpen.value = true
}

async function changeCreateDataset(datasetId) {
  createDatasetId.value = datasetId
  await loadCreateDatasetVersions(datasetId)
}

async function loadCreateDatasetVersions(datasetId) {
  createDatasetVersions.value = []
  createDatasetVersionId.value = ''
  if (!datasetId) return
  const requestId = ++createVersionRequestId.value
  loadingCreateVersions.value = true
  try {
    const versions = await getDatasetVersions(datasetId)
    if (requestId !== createVersionRequestId.value) return
    createDatasetVersions.value = (versions || []).filter((version) => version.status === 'READY')
    const dataset = createDatasetOptions.value.find((item) => (item.datasetId || item.id) === datasetId)
    createDatasetVersionId.value = dataset?.datasetVersionId
      || dataset?.id
      || createDatasetVersions.value[0]?.datasetVersionId
      || ''
  } catch (error) {
    if (!handleAuthError(error)) createDialogError.value = error?.message || '读取数据集版本失败'
  } finally {
    if (requestId === createVersionRequestId.value) loadingCreateVersions.value = false
  }
}

async function confirmCreateLibrary() {
  if (!createDatasetVersionId.value) {
    createDialogError.value = '请选择一个 READY 数据集版本'
    return
  }
  createDialogError.value = ''
  createDialogOpen.value = false
  await createLibrary()
}

async function removeRule(rule) {
  if (!activeRuleSheet.value) {
    return
  }
  const definition = String(rule.cells?.legacyDefinition || '').trim()
  if (definition) {
    try {
      await ElMessageBox.confirm('删除这条规则及其定义内容？', '删除规则', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
  }
  activeRuleSheet.value.rows = activeRuleSheet.value.rows.filter((item) => item.id !== rule.id)
  if (activeWorkbook.value) {
    ruleCounts[activeWorkbook.value.id] = activeRuleSheet.value.rows.length
  }
}

function ruleNumber(index) {
  return activeRulePage.value * RULE_PAGE_SIZE + index + 1
}

function libraryRuleCountLabel(library) {
  const value = ruleCounts[library.id]
  return value == null ? '读取中' : `${value} 条规则`
}

function handleGlobalKeydown(event) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
    if (!activeWorkbook.value) {
      return
    }
    event.preventDefault()
    persistDraft()
  }
}

onMounted(() => {
  loadDatasets()
  loadWorkbooks()
  window.addEventListener('keydown', handleGlobalKeydown)
})

watch(
  () => activeWorkbook.value?.datasetVersionId,
  (datasetVersionId) => loadDatasetCatalog(datasetVersionId),
  { immediate: true },
)

onBeforeUnmount(() => {
  clearAutoSave()
  stopPreviewPolling()
  if (dirty.value) {
    persistDraft({ silent: true })
  }
  window.removeEventListener('keydown', handleGlobalKeydown)
})
</script>

<template>
  <div class="page-content">
    <el-breadcrumb separator="/" class="page-breadcrumb">
      <el-breadcrumb-item>工作台</el-breadcrumb-item>
      <el-breadcrumb-item>规则库</el-breadcrumb-item>
    </el-breadcrumb>

    <header class="page-header">
      <div>
        <h1>规则库</h1>
        <p>按「规则库 → 结构化节点」编排数据检查、清洗与筛选，不需要编写 SQL。</p>
      </div>
      <el-button
        type="primary"
        size="large"
        :icon="FolderAdd"
        :loading="creatingLibrary"
        @click="openCreateDialog"
      >
        新建规则库
      </el-button>
    </header>

    <el-card class="toolbar-card" shadow="never">
      <div class="library-toolbar">
        <el-input
          v-model="libraryKeyword"
          class="search-input"
          size="large"
          placeholder="搜索规则库名称"
          clearable
          :prefix-icon="Search"
          @keyup.enter="searchLibraries"
          @clear="searchLibraries"
        />
        <el-button size="large" :icon="Search" @click="searchLibraries">搜索</el-button>
        <el-button size="large" :icon="Refresh" @click="resetLibrarySearch">重置</el-button>
        <span class="toolbar-spacer"></span>
        <span class="toolbar-count">
          当前共 <strong>{{ workbooks.length }}</strong> 个规则库
        </span>
      </div>
    </el-card>

    <el-card class="list-card" shadow="never">
      <template #header>
        <div class="list-header">
          <span class="list-title">规则库目录</span>
          <span class="list-hint">
            {{ activeWorkbookId ? '点击展开的规则库行可以收起，编辑会自动保存' : '点击规则库展开规则明细，名称可以行内重命名' }}
          </span>
        </div>
      </template>

      <div v-loading="loadingWorkbooks" class="library-list">
        <article
          v-for="library in pagedLibraries"
          :key="library.id"
          class="library-item"
          :class="{ 'is-expanded': activeWorkbookId === library.id }"
        >
          <div class="library-summary" @click="toggleLibrary(library)">
            <span class="row-chevron" :class="{ 'is-open': activeWorkbookId === library.id }">
              <el-icon><ArrowRight /></el-icon>
            </span>

            <div class="library-name-block">
              <el-input
                v-if="editingLibraryId === library.id"
                :ref="(el) => setLibraryNameInput(el, library.id)"
                v-model="libraryNameDraft"
                class="name-input"
                size="large"
                maxlength="255"
                @click.stop
                @keyup.enter="handleLibraryNameEnter"
                @keyup.esc="cancelLibraryEdit"
                @blur="commitLibraryName(library)"
              />
              <button
                v-else
                type="button"
                class="name-link"
                :title="library.name"
                @click.stop="toggleLibrary(library)"
              >
                {{ library.name }}
              </button>
              <span class="library-description" :title="library.description || ''">
                {{ library.description || '可组合、可校验的数据处理节点' }}
              </span>
            </div>

            <div class="summary-metrics">
              <span class="metric-chip">
                <el-icon><Grid /></el-icon>
                {{ libraryRuleCountLabel(library) }}
              </span>
              <span class="metric-chip is-muted">修订 {{ library.revision }}</span>
            </div>

            <div class="row-actions" @click.stop>
              <el-button link type="primary" :icon="EditPen" @click="startRenameLibrary(library)">
                重命名
              </el-button>
              <el-button link type="danger" :icon="Delete" @click="removeLibrary(library)">
                删除
              </el-button>
            </div>
          </div>
          <section
            v-if="activeWorkbookId === library.id"
            v-loading="loadingEditor"
            class="library-expanded"
            @click.stop
          >
            <div v-if="activeWorkbook" class="expanded-inner">
              <div class="rule-editor-head">
                <div class="rule-editor-title">
                  <strong>处理节点</strong>
                  <span>{{ activeRuleTotal }} 条规则 · 关联数据集：{{ activeDatasetName }}</span>
                </div>
                <div class="rule-editor-actions">
                  <span v-if="saving" class="save-state">保存中…</span>
                  <span v-else-if="dirty" class="save-state is-dirty">有未保存修改</span>
                  <span v-else-if="lastSavedAt" class="save-state">
                    已保存 {{ formatTime(lastSavedAt) }}
                  </span>
                  <el-button :icon="Refresh" :loading="saving" @click="persistDraft()">
                    保存
                  </el-button>
                  <el-button :loading="validating" @click="validateWorkbook">校验流程</el-button>
                  <el-button v-if="previewRun" @click="openPreviewResults">查看结果</el-button>
                  <el-button :loading="previewStarting" @click="startRulePreview">预览运行</el-button>
                  <el-button type="primary" :loading="publishing" :disabled="activeWorkbook.status === 'PUBLISHED'" @click="publishWorkbook">
                    发布版本
                  </el-button>
                </div>
              </div>

              <p class="rule-hint">
                <span v-if="migratingNotice">
                  已将 {{ migratingNotice }} 条旧版文本保留为只读历史记录；
                </span>
                配置仅包含白名单操作和字段；校验由服务端完成并生成可追溯的编译计划。
              </p>

              <el-alert
                v-if="validationResult"
                class="validation-alert"
                :type="validationResult.valid ? 'success' : 'warning'"
                :closable="false"
                :title="validationResult.valid ? `校验通过：${validationResult.nodeCount} 个节点` : '请修复以下配置问题后再发布'"
              >
                <ul v-if="!validationResult.valid" class="validation-issues">
                  <li v-for="issue in validationResult.issues" :key="`${issue.code}-${issue.rowId}-${issue.columnKey}`">
                    {{ issue.message }}<span v-if="issue.fixSuggestion">（{{ issue.fixSuggestion }}）</span>
                  </li>
                </ul>
              </el-alert>

              <div class="rule-toolbar">
                <el-input
                  v-model="ruleSearch[activeWorkbook.id].input"
                  class="rule-search-input"
                  size="small"
                  placeholder="搜索节点名称或类型"
                  clearable
                  :prefix-icon="Search"
                  @keyup.enter="applyRuleSearch"
                  @clear="resetRuleSearch"
                />
                <el-button size="small" :icon="Search" @click="applyRuleSearch">搜索</el-button>
                <el-button size="small" @click="resetRuleSearch">重置</el-button>
                <span class="rule-filter-count">
                  已筛选 <strong>{{ activeRules.length }}</strong> / {{ activeRuleTotal }} 条规则
                </span>
                <span class="toolbar-spacer"></span>
              </div>

              <div v-if="pagedActiveRules.length" class="rule-list">
                <article
                  v-for="(rule, index) in pagedActiveRules"
                  :key="rule.id"
                  class="rule-item"
                >
                  <div class="rule-head">
                    <span class="rule-index">{{ ruleNumber(index) }}</span>

                    <el-input
                      :ref="(el) => setRuleNameInput(el, rule.id)"
                      v-model="rule.cells.name"
                      class="rule-name-input"
                      maxlength="120"
                      placeholder="给这条规则起个名字"
                    />

                    <el-select
                      :model-value="rule.cells.nodeKind"
                      class="rule-template-select"
                      :disabled="rule.cells.nodeKind === 'LEGACY_NOTE'"
                      @update:model-value="changeNodeKind(rule, $event)"
                    >
                      <el-option
                        v-for="kind in ruleNodeKinds"
                        :key="kind.id"
                        :label="kind.label"
                        :value="kind.id"
                      >
                        <span class="template-option">
                          <strong>{{ kind.label }}</strong>
                          <small>{{ kind.summary }}</small>
                        </span>
                      </el-option>
                      <el-option v-if="rule.cells.nodeKind === 'LEGACY_NOTE'" label="旧版记录" value="LEGACY_NOTE" />
                    </el-select>

                    <el-switch
                      v-model="rule.enabled"
                      class="rule-enabled"
                      inline-prompt
                      active-text="启用"
                      inactive-text="停用"
                    />

                    <el-button
                      link
                      type="danger"
                      :icon="Delete"
                      class="rule-delete"
                      @click="removeRule(rule)"
                    >
                      删除
                    </el-button>
                  </div>

                  <div v-if="rule.cells.nodeKind === 'LEGACY_NOTE'" class="legacy-definition">
                    <span>历史文本记录（只读，不会进入执行计划）</span>
                    <el-input :model-value="rule.cells.legacyDefinition" type="textarea" readonly :autosize="{ minRows: 2, maxRows: 8 }" />
                  </div>

                  <div v-else class="structured-definition">
                    <div v-if="rule.cells.nodeKind !== 'JOIN'" class="config-row">
                      <span class="definition-label">数据输入</span>
                      <el-select
                        class="config-control config-table"
                        :model-value="ruleInputValue(rule)"
                        filterable
                        :loading="loadingCatalog"
                        placeholder="选择数据表或上游输出"
                        @update:model-value="setRuleInput(rule, $event)"
                      >
                        <el-option-group label="数据表">
                          <el-option v-for="table in activeTables" :key="table.tableId" :label="table.tableName" :value="`table:${table.tableId}`">
                            <span class="input-option" :title="table.tableName">
                              <strong>{{ table.tableName }}</strong>
                              <small>数据表</small>
                            </span>
                          </el-option>
                        </el-option-group>
                        <el-option-group v-if="outputRulesFor(rule).length" label="上游节点输出">
                          <el-option
                            v-for="upstream in outputRulesFor(rule)"
                            :key="ruleNodeId(upstream)"
                            :label="`${upstream.cells.name || nodeKindLabel(upstream.cells.nodeKind)} · ${ruleOutputAlias(upstream)}`"
                            :value="`node:${ruleNodeId(upstream)}`"
                          >
                            <span
                              class="input-option"
                              :title="`${upstream.cells.name || nodeKindLabel(upstream.cells.nodeKind)} · ${ruleOutputAlias(upstream)}`"
                            >
                              <strong>{{ upstream.cells.name || nodeKindLabel(upstream.cells.nodeKind) }}</strong>
                              <small>{{ ruleOutputAlias(upstream) }}</small>
                            </span>
                          </el-option>
                        </el-option-group>
                      </el-select>
                      <span class="config-note">字段会跟随数据表或上游节点结果更新</span>
                    </div>

                    <template v-if="rule.cells.nodeKind === 'JOIN'">
                      <div class="join-input-grid">
                        <div v-for="side in ['left', 'right']" :key="side" class="join-input-panel">
                          <span class="join-input-label">{{ side === 'left' ? '左侧数据' : '右侧数据' }}</span>
                          <el-select
                            class="join-input-select"
                            :model-value="joinInputValue(rule, side)"
                            :title="selectedJoinInputTitle(rule, side)"
                            filterable
                            :loading="loadingCatalog"
                            :placeholder="side === 'left' ? '选择左侧数据源' : '选择右侧数据源'"
                            @update:model-value="setJoinInput(rule, side, $event)"
                          >
                            <el-option-group label="数据表">
                              <el-option
                                v-for="table in activeTables"
                                :key="`${side}:${table.tableId}`"
                                :label="table.tableName"
                                :value="`table:${table.tableId}`"
                                :disabled="joinSelectionDisabled(rule, side, `table:${table.tableId}`)"
                              >
                                <span class="input-option" :title="table.tableName">
                                  <strong>{{ table.tableName }}</strong>
                                  <small>数据表</small>
                                </span>
                              </el-option>
                            </el-option-group>
                            <el-option-group v-if="outputRulesFor(rule).length" label="上游节点输出">
                              <el-option
                                v-for="upstream in outputRulesFor(rule)"
                                :key="`${side}:${ruleNodeId(upstream)}`"
                                :label="`${upstream.cells.name || nodeKindLabel(upstream.cells.nodeKind)} · ${ruleOutputAlias(upstream)}`"
                                :value="`node:${ruleNodeId(upstream)}`"
                                :disabled="joinSelectionDisabled(rule, side, `node:${ruleNodeId(upstream)}`)"
                              >
                                <span
                                  class="input-option"
                                  :title="`${upstream.cells.name || nodeKindLabel(upstream.cells.nodeKind)} · ${ruleOutputAlias(upstream)}`"
                                >
                                  <strong>{{ upstream.cells.name || nodeKindLabel(upstream.cells.nodeKind) }}</strong>
                                  <small>{{ ruleOutputAlias(upstream) }}</small>
                                </span>
                              </el-option>
                            </el-option-group>
                          </el-select>
                          <small>{{ joinColumns(rule, side).length }} 个可用字段</small>
                        </div>
                      </div>

                      <div class="join-key-editor">
                        <div class="join-key-head">
                          <span>关联键</span>
                          <small>左右字段按行一一对应，可组合多个字段</small>
                          <el-button link type="primary" :disabled="!joinInput(rule, 'left') || !joinInput(rule, 'right')" @click="addJoinKey(rule)">
                            + 添加字段对
                          </el-button>
                        </div>
                        <div v-if="!joinKeyRows(rule)" class="join-key-empty">选择两个数据源后添加关联字段</div>
                        <div v-for="index in joinKeyRows(rule)" :key="index" class="join-key-row">
                          <span class="metric-order">{{ index }}</span>
                          <el-select
                            :model-value="fieldColumnId(rule.cells.config.leftKeys[index - 1])"
                            :title="selectedJoinFieldTitle(rule, 'left', index - 1)"
                            filterable
                            placeholder="左侧字段"
                            @update:model-value="updateJoinKey(rule, 'left', index - 1, $event)"
                          >
                            <el-option
                              v-for="column in joinColumns(rule, 'left')"
                              :key="column.columnId"
                              :label="columnOptionLabel(column)"
                              :value="column.columnId"
                            >
                              <span class="field-option" :title="columnOptionLabel(column)">{{ columnOptionLabel(column) }}</span>
                            </el-option>
                          </el-select>
                          <span class="join-key-equals">=</span>
                          <el-select
                            :model-value="fieldColumnId(rule.cells.config.rightKeys[index - 1])"
                            :title="selectedJoinFieldTitle(rule, 'right', index - 1)"
                            filterable
                            placeholder="右侧字段"
                            @update:model-value="updateJoinKey(rule, 'right', index - 1, $event)"
                          >
                            <el-option
                              v-for="column in joinColumns(rule, 'right')"
                              :key="column.columnId"
                              :label="columnOptionLabel(column)"
                              :value="column.columnId"
                            >
                              <span class="field-option" :title="columnOptionLabel(column)">{{ columnOptionLabel(column) }}</span>
                            </el-option>
                          </el-select>
                          <el-button link type="danger" @click="removeJoinKey(rule, index - 1)">删除</el-button>
                        </div>
                      </div>

                      <div class="join-settings-grid">
                        <label class="join-setting">
                          <span>关联方式</span>
                          <el-select v-model="rule.cells.config.joinType">
                            <el-option v-for="option in joinTypes" :key="option.id" :label="option.label" :value="option.id" />
                          </el-select>
                        </label>
                        <label class="join-setting">
                          <span>数据基数</span>
                          <el-select v-model="rule.cells.config.cardinality">
                            <el-option v-for="option in joinCardinalities" :key="option.id" :label="option.label" :value="option.id" />
                          </el-select>
                        </label>
                        <label class="join-setting">
                          <span>最小匹配率</span>
                          <el-input-number v-model="rule.cells.config.guards.minMatchRate" :min="0" :max="1" :step="0.05" :precision="2" controls-position="right" />
                        </label>
                        <label class="join-setting">
                          <span>最大展开倍数</span>
                          <el-input-number v-model="rule.cells.config.guards.maxFanout" :min="0" :max="1000" :step="1" controls-position="right" />
                        </label>
                        <label class="join-setting join-switch-setting">
                          <span>右侧键必须唯一</span>
                          <el-switch v-model="rule.cells.config.guards.rightKeyUnique" />
                        </label>
                        <label class="join-setting">
                          <span>护栏失败时</span>
                          <el-select v-model="rule.cells.config.guards.onViolation">
                            <el-option label="阻止运行" value="FAIL_RUN" />
                            <el-option label="记录警告并继续" value="WARN" />
                          </el-select>
                        </label>
                      </div>
                      <div class="config-row join-output-row">
                        <span class="definition-label">输出别名</span>
                        <el-input
                          :model-value="rule.cells.config.outputAlias"
                          class="config-control"
                          maxlength="120"
                          placeholder="关联结果名称"
                          @update:model-value="updateOutputAlias(rule, $event)"
                        />
                        <span class="config-note">预览阶段固定使用“发现重复即失败”，避免静默丢弃记录</span>
                      </div>
                    </template>

                    <template v-else-if="['CHECK', 'FILTER'].includes(rule.cells.nodeKind)">
                      <div v-for="(condition, conditionIndex) in conditionsFor(rule)" :key="conditionIndex" class="condition-row">
                        <el-select
                          class="config-control config-field"
                          :model-value="fieldColumnId(condition.field || condition.args?.[0])"
                          placeholder="选择字段"
                          :disabled="!ruleInput(rule)"
                          @update:model-value="updateConditionField(rule, condition, $event)"
                        >
                          <el-option v-for="column in ruleColumns(rule)" :key="column.columnId" :label="columnOptionLabel(column)" :value="column.columnId" />
                        </el-select>
                        <el-select class="config-control config-operator" :model-value="condition.op" @update:model-value="changePredicateOperator(condition, $event)">
                          <el-option v-for="operator in predicateOperators" :key="operator.id" :label="operator.label" :value="operator.id" />
                        </el-select>
                        <el-input v-if="!['IS_NULL', 'IS_EMPTY'].includes(condition.op)" v-model="condition.value" class="config-control config-value" placeholder="比较值" />
                        <el-input v-if="condition.op === 'BETWEEN'" v-model="condition.upperValue" class="config-control config-value" placeholder="上限" />
                        <el-button link type="danger" @click="removeCondition(rule, conditionIndex)">移除</el-button>
                      </div>
                      <el-button class="add-condition" link type="primary" @click="addCondition(rule)">+ 添加条件</el-button>
                      <div v-if="rule.cells.nodeKind === 'CHECK'" class="config-row">
                        <span class="definition-label">命中效果</span>
                        <el-select v-model="rule.cells.config.effect" class="config-control config-effect">
                          <el-option label="仅记录提示" value="OBSERVE" />
                          <el-option label="标记警告" value="WARN" />
                          <el-option label="阻断发布" value="BLOCK_PUBLISH" />
                          <el-option label="移除记录" value="DROP_ROWS" />
                          <el-option label="隔离记录" value="QUARANTINE_ROWS" />
                        </el-select>
                        <el-input :model-value="rule.cells.config.outputAlias" class="config-control" placeholder="输出别名，如 clean_rows" @update:model-value="updateOutputAlias(rule, $event)" />
                      </div>
                      <div v-else class="config-row">
                        <span class="definition-label">输出别名</span>
                        <el-input :model-value="rule.cells.config.outputAlias" class="config-control" placeholder="筛选结果名称" @update:model-value="updateOutputAlias(rule, $event)" />
                      </div>
                    </template>

                    <template v-else-if="rule.cells.nodeKind === 'TRANSFORM'">
                      <div class="config-row">
                        <span class="definition-label">作用字段</span>
                        <el-select class="config-control config-field" :model-value="fieldColumnId(rule.cells.config.target)" @update:model-value="rule.cells.config.target = fieldRef(rule, $event)">
                          <el-option v-for="column in ruleColumns(rule)" :key="column.columnId" :label="columnOptionLabel(column)" :value="column.columnId" />
                        </el-select>
                        <el-select v-model="rule.cells.config.operation" class="config-control config-operation">
                          <el-option v-for="operation in transformOperations" :key="operation.id" :label="operation.label" :value="operation.id" />
                        </el-select>
                        <el-input v-if="rule.cells.config.operation === 'FILL_VALUE'" v-model="rule.cells.config.params.value" class="config-control config-value" placeholder="缺失值填充内容" />
                        <template v-if="rule.cells.config.operation === 'REPLACE'">
                          <el-input v-model="rule.cells.config.params.find" class="config-control config-value" placeholder="查找文本" />
                          <el-input v-model="rule.cells.config.params.replacement" class="config-control config-value" placeholder="替换为" />
                        </template>
                        <el-select v-if="rule.cells.config.operation === 'CAST'" v-model="rule.cells.config.params.dataType" class="config-control">
                          <el-option v-for="type in transformTypes" :key="type.id" :label="type.label" :value="type.id" />
                        </el-select>
                      </div>
                      <div class="config-row">
                        <span class="definition-label">输出别名</span>
                        <el-input :model-value="rule.cells.config.outputAlias" class="config-control" placeholder="转换结果名称" @update:model-value="updateOutputAlias(rule, $event)" />
                      </div>
                    </template>

                    <template v-else-if="rule.cells.nodeKind === 'PROJECT'">
                      <div class="config-row">
                        <span class="definition-label">保留字段</span>
                        <el-select class="config-control config-multi" multiple collapse-tags :model-value="selectedFieldIds(rule.cells.config.select)" @update:model-value="updateFieldList(rule, 'select', $event)">
                          <el-option v-for="column in ruleColumns(rule)" :key="column.columnId" :label="columnOptionLabel(column)" :value="column.columnId" />
                        </el-select>
                        <el-input :model-value="rule.cells.config.outputAlias" class="config-control" placeholder="输出别名" @update:model-value="updateOutputAlias(rule, $event)" />
                      </div>
                    </template>

                    <template v-else-if="rule.cells.nodeKind === 'DEDUPE'">
                      <div class="config-row">
                        <span class="definition-label">去重键</span>
                        <el-select class="config-control config-multi" multiple collapse-tags :model-value="selectedFieldIds(rule.cells.config.keys)" @update:model-value="updateFieldList(rule, 'keys', $event)">
                          <el-option v-for="column in ruleColumns(rule)" :key="column.columnId" :label="columnOptionLabel(column)" :value="column.columnId" />
                        </el-select>
                        <el-select v-model="rule.cells.config.keep" class="config-control"><el-option label="保留第一条" value="FIRST" /><el-option label="保留最后一条" value="LAST" /></el-select>
                        <el-input :model-value="rule.cells.config.outputAlias" class="config-control" placeholder="输出别名" @update:model-value="updateOutputAlias(rule, $event)" />
                      </div>
                    </template>

                    <template v-else-if="rule.cells.nodeKind === 'AGGREGATE'">
                      <div class="config-row aggregate-group-row">
                        <span class="definition-label">分组字段</span>
                        <el-select
                          class="config-control config-multi"
                          multiple
                          collapse-tags
                          collapse-tags-tooltip
                          :model-value="selectedFieldIds(rule.cells.config.groupBy)"
                          placeholder="不选择时对全部记录聚合"
                          @update:model-value="updateFieldList(rule, 'groupBy', $event)"
                        >
                          <el-option
                            v-for="column in ruleColumns(rule)"
                            :key="column.columnId"
                            :label="columnOptionLabel(column)"
                            :value="column.columnId"
                          >
                            <span class="field-option" :title="columnOptionLabel(column)">{{ columnOptionLabel(column) }}</span>
                          </el-option>
                        </el-select>
                        <el-input
                          :model-value="rule.cells.config.outputAlias"
                          class="config-control aggregate-output-alias"
                          maxlength="120"
                          placeholder="输出别名"
                          @update:model-value="updateOutputAlias(rule, $event)"
                        />
                      </div>
                      <div class="aggregate-editor">
                        <div class="aggregate-editor-head">
                          <span class="definition-label">统计指标</span>
                          <span>指标会成为下游可选择的新字段</span>
                          <el-button link type="primary" @click="addAggregateMetric(rule)">+ 添加指标</el-button>
                        </div>
                        <div
                          v-for="(metric, metricIndex) in rule.cells.config.metrics"
                          :key="metricIndex"
                          class="aggregate-metric-row"
                        >
                          <span class="metric-order">{{ metricIndex + 1 }}</span>
                          <el-input
                            v-model="metric.name"
                            class="metric-name-control"
                            maxlength="255"
                            placeholder="指标字段名"
                          />
                          <el-select
                            :model-value="metric.op"
                            class="metric-operation-control"
                            @update:model-value="updateAggregateMetricOperation(rule, metric, $event)"
                          >
                            <el-option v-for="operation in aggregateOperations" :key="operation.id" :label="operation.label" :value="operation.id" />
                          </el-select>
                          <el-select
                            :model-value="aggregateMetricFieldValue(metric)"
                            class="metric-field-control"
                            filterable
                            placeholder="选择统计字段"
                            @update:model-value="updateAggregateMetricField(rule, metric, $event)"
                          >
                            <el-option v-if="metric.op === 'COUNT'" label="记录数（无需字段）" value="*" />
                            <el-option
                              v-for="column in ruleColumns(rule)"
                              :key="column.columnId"
                              :label="columnOptionLabel(column)"
                              :value="column.columnId"
                            >
                              <span class="field-option" :title="columnOptionLabel(column)">{{ columnOptionLabel(column) }}</span>
                            </el-option>
                          </el-select>
                          <el-button
                            link
                            type="danger"
                            :disabled="rule.cells.config.metrics.length <= 1"
                            @click="removeAggregateMetric(rule, metricIndex)"
                          >
                            删除
                          </el-button>
                        </div>
                      </div>
                    </template>

                    <template v-else-if="rule.cells.nodeKind === 'DERIVE'">
                      <div class="derive-basics">
                        <div class="config-row derive-basic-row">
                          <span class="definition-label">新字段</span>
                          <el-input
                            v-model="rule.cells.config.outputField"
                            class="config-control derive-field-name"
                            maxlength="255"
                            show-word-limit
                            placeholder="例如 delivery_delay_days"
                          />
                          <el-select v-model="rule.cells.config.dataType" class="config-control derive-data-type">
                            <el-option v-for="type in deriveTypes" :key="type.id" :label="type.label" :value="type.id" />
                          </el-select>
                          <el-checkbox v-model="rule.cells.config.replaceExisting">允许覆盖同名字段</el-checkbox>
                        </div>
                        <div class="config-row derive-basic-row">
                          <span class="definition-label">输出别名</span>
                          <el-input
                            :model-value="rule.cells.config.outputAlias"
                            class="config-control derive-output-alias"
                            maxlength="120"
                            placeholder="供下游节点引用"
                            @update:model-value="updateOutputAlias(rule, $event)"
                          />
                          <span class="config-note">默认新增字段；只有明确勾选时才替换同名字段</span>
                        </div>
                      </div>
                      <div class="derive-expression-row">
                        <span class="definition-label">表达式</span>
                        <DeriveExpressionEditor
                          v-model="rule.cells.config.expression"
                          class="derive-expression-editor"
                          :columns="ruleColumns(rule)"
                          :input-alias="ruleInputAlias(rule)"
                        />
                      </div>
                    </template>
                  </div>
                </article>
              </div>

              <el-empty
                v-else-if="!activeRuleTotal"
                class="rule-empty"
                :image-size="72"
                description="还没有规则，点击下方「添加一条规则」开始"
              />

              <el-empty
                v-else
                class="rule-empty"
                :image-size="64"
                :description="`没有匹配「${activeSearchQuery}」的规则`"
              >
                <el-button :icon="Refresh" @click="resetRuleSearch">清除筛选</el-button>
              </el-empty>

              <button
                v-if="!activeSearchQuery"
                type="button"
                class="rule-add-row"
                @click="addRule"
              >
                <el-icon><Plus /></el-icon>
                <span>添加一条规则</span>
                <small>同时创建规则表头和定义内容</small>
              </button>

              <div v-if="activeRules.length > RULE_PAGE_SIZE" class="rule-pagination">
                <span class="pagination-hint">共 {{ activeRules.length }} 条规则</span>
                <el-pagination
                  :current-page="activeRulePage + 1"
                  :page-size="RULE_PAGE_SIZE"
                  :total="activeRules.length"
                  layout="prev, pager, next"
                  background
                  @current-change="handleRulePageChange"
                />
              </div>
            </div>
          </section>
        </article>

        <el-empty
          v-if="!loadingWorkbooks && workbooks.length === 0"
          class="empty-state"
          :image-size="104"
          description="还没有规则库"
        >
          <el-button type="primary" :icon="FolderAdd" @click="openCreateDialog">
            新建第一个规则库
          </el-button>
        </el-empty>

        <div v-if="showLibraryPagination" class="library-pagination">
          <span class="pagination-hint">每页 {{ libraryPageSize }} 个规则库</span>
          <el-pagination
            :current-page="libraryPage + 1"
            :page-size="libraryPageSize"
            :page-sizes="[5, 10, 20]"
            :total="workbooks.length"
            layout="sizes, prev, pager, next, jumper"
            background
            @current-change="handleLibraryPageChange"
            @size-change="changeLibrarySize"
          />
        </div>
      </div>
    </el-card>

    <el-dialog v-model="createDialogOpen" title="新建规则库" width="min(520px, calc(100vw - 32px))">
      <p class="dialog-copy">规则库会绑定一个 READY 数据集版本，后续可以在其中复用多个结构化处理节点。</p>
      <el-form label-position="top">
        <el-form-item label="数据集库" required>
          <el-select :model-value="createDatasetId" filterable class="dialog-select" placeholder="选择数据集库" @update:model-value="changeCreateDataset">
            <el-option v-for="dataset in createDatasetOptions" :key="dataset.datasetId || dataset.id" :label="dataset.name || dataset.originalFilename" :value="dataset.datasetId || dataset.id">
              <span class="dataset-option"><strong>{{ dataset.name || dataset.originalFilename }}</strong><small>{{ dataset.tableCount }} 张表 · {{ dataset.rowCount }} 行</small></span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="READY 数据集版本" required>
          <el-select v-model="createDatasetVersionId" filterable class="dialog-select" :loading="loadingCreateVersions" placeholder="选择数据集版本">
            <el-option v-for="version in createDatasetVersions" :key="version.datasetVersionId" :label="`v${version.versionNo} · ${version.rowCount} 行`" :value="version.datasetVersionId" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-alert v-if="createDialogError" type="warning" :closable="false" :title="createDialogError" />
      <template #footer>
        <el-button @click="createDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="creatingLibrary" @click="confirmCreateLibrary">创建并配置</el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="previewDrawerOpen"
      class="preview-drawer"
      title="规则预览运行"
      size="min(980px, 94vw)"
      destroy-on-close
      @opened="handlePreviewDrawerOpened"
      @closed="stopPreviewPolling"
    >
      <div class="preview-toolbar">
        <el-input-number v-model="previewSampleSize" :min="1" :max="5000" :step="50" controls-position="right" />
        <span class="preview-toolbar-note">每个节点最多展示 100 行样例，查询仍受只读与资源上限保护</span>
        <span class="toolbar-spacer"></span>
        <el-button type="primary" :loading="previewStarting" @click="startRulePreview">创建新预览</el-button>
      </div>

      <div v-if="previewHistory.length" class="preview-history-row">
        <span>运行记录</span>
        <el-select
          :model-value="previewRun?.id"
          class="preview-history-select"
          placeholder="选择一次运行"
          @update:model-value="selectPreviewRun"
        >
          <el-option v-for="run in previewHistory" :key="run.id" :label="previewRunOption(run)" :value="run.id" />
        </el-select>
      </div>

      <el-alert
        v-if="previewRun"
        class="preview-run-alert"
        :type="previewRun.status === 'FAILED' ? 'error' : previewRun.status === 'SUCCEEDED' ? 'success' : 'info'"
        :closable="false"
        :title="`运行状态：${previewRunStatusLabel(previewRun.status)}`"
      >
        <template #default>
          <span v-if="previewRun.errorMessage">{{ previewRun.errorMessage }}</span>
          <span v-else>{{ previewRunSummary(previewRun) }}</span>
        </template>
      </el-alert>

      <div v-if="previewRun" class="preview-run-meta" aria-label="运行执行信息">
        <span>模式 {{ previewRun.mode === 'PREVIEW' ? '预览' : previewRun.mode }}</span>
        <span v-if="previewRun.executionAttempt > 0">执行尝试 {{ previewRun.executionAttempt }}</span>
        <span v-if="previewRun.retryOfRunId" :title="`来源运行 ${previewRun.retryOfRunId}`">
          重试自 {{ previewRun.retryOfRunId.slice(0, 8) }}
        </span>
        <span v-if="previewRun.claimedBy" class="preview-worker">
          Worker
          <strong :title="previewRun.claimedBy">{{ previewRun.claimedBy }}</strong>
        </span>
        <span
          v-if="previewRun.leaseExpiresAt"
          :class="{ 'is-expired': !previewRunLeaseActive(previewRun) }"
          :title="formatTime(previewRun.leaseExpiresAt)"
        >
          {{ previewRunLeaseLabel(previewRun) }}
        </span>
        <span class="preview-run-actions">
          <el-button
            v-if="['QUEUED', 'RUNNING'].includes(previewRun.status)"
            size="small"
            type="danger"
            plain
            :loading="previewCancelling"
            @click="cancelPreviewRun"
          >取消运行</el-button>
          <el-button
            v-if="['FAILED', 'CANCELLED'].includes(previewRun.status)"
            size="small"
            type="primary"
            plain
            :loading="previewRetrying"
            @click="retryPreviewRun"
          >安全重试</el-button>
        </span>
      </div>

      <div v-loading="loadingPreview" class="preview-layout">
        <aside class="preview-node-list">
          <button
            v-for="node in previewDisplayNodes"
            :key="node.nodeId"
            type="button"
            class="preview-node-item"
            :class="{ 'is-active': previewSelectedNodeId === node.nodeId }"
            @click="previewSelectedNodeId = node.nodeId"
          >
            <span class="preview-node-main">
              <strong :title="previewNodeName(node)">{{ previewNodeName(node) }}</strong>
              <small>{{ nodeKindLabel(node.nodeKind) }}</small>
            </span>
            <el-tag size="small" :type="node.status === 'FAILED' ? 'danger' : node.status === 'SUCCEEDED' ? 'success' : node.status === 'RUNNING' ? 'primary' : node.status === 'CANCELLED' ? 'warning' : 'info'">
              {{ previewNodeStatusLabel(node.status) }}
            </el-tag>
          </button>
        </aside>

        <section class="preview-result-panel">
          <template v-if="previewCurrentNode">
            <div class="preview-result-head">
              <div>
                <strong :title="previewNodeName(previewCurrentNode)">{{ previewNodeName(previewCurrentNode) }}</strong>
                <span>{{ previewCurrentNode.nodeId }}</span>
              </div>
              <el-tag :type="previewCurrentNode.status === 'FAILED' ? 'danger' : previewCurrentNode.status === 'SUCCEEDED' ? 'success' : previewCurrentNode.status === 'CANCELLED' ? 'warning' : 'info'">
                {{ previewNodeStatusLabel(previewCurrentNode.status) }}
              </el-tag>
            </div>
            <el-alert v-if="previewCurrentNode.errorMessage" type="error" :closable="false" :title="previewCurrentNode.errorMessage" />
            <div v-if="previewMetricCards.length" class="preview-metrics">
              <div v-for="([label, value]) in previewMetricCards" :key="label" class="preview-metric-card">
                <span>{{ label }}</span><strong>{{ value }}</strong>
              </div>
            </div>
            <div v-if="previewSampleRows.length" class="preview-table-wrap">
              <el-table :data="previewSampleRows" max-height="480" border stripe>
                <el-table-column
                  v-for="column in previewSampleColumns"
                  :key="column.name"
                  :prop="column.name"
                  min-width="160"
                  show-overflow-tooltip
                >
                  <template #header><span :title="column.name">{{ column.name }}</span></template>
                </el-table-column>
              </el-table>
            </div>
            <el-empty v-else :image-size="64" description="该节点尚未返回样例数据" />
          </template>
          <el-empty v-else :image-size="72" description="选择左侧节点查看结果" />
        </section>
      </div>
    </el-drawer>
  </div>
</template>
<style scoped>
.library-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.search-input {
  width: 280px;
}

.toolbar-spacer {
  flex: 1;
}

.toolbar-count {
  color: var(--ink-faint);
  font-size: 12px;
}

.toolbar-count strong {
  color: var(--ink);
}

.list-title {
  color: var(--ink);
  font-size: 14px;
  font-weight: 650;
}

.list-hint {
  color: var(--ink-faint);
  font-size: 12px;
}

.library-list {
  min-height: 160px;
}

.library-item {
  border-bottom: 1px solid var(--line);
}

.library-item:last-child {
  border-bottom: 0;
}

.library-summary {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 20px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.library-summary:hover {
  background: rgba(15, 108, 189, 0.04);
}

.library-item.is-expanded .library-summary {
  background: rgba(15, 108, 189, 0.055);
}

.row-chevron {
  display: inline-grid;
  width: 20px;
  height: 20px;
  flex: 0 0 20px;
  place-items: center;
  color: var(--ink-faint);
  transition: transform 0.18s ease;
}

.row-chevron.is-open {
  transform: rotate(90deg);
}

.library-name-block {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}

.name-link {
  overflow: hidden;
  padding: 0;
  color: var(--ink);
  font-size: 14px;
  font-weight: 650;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
  background: none;
  border: 0;
  cursor: pointer;
}

.name-link:hover {
  color: var(--accent);
}

.name-input {
  width: 300px;
}

.library-description {
  overflow: hidden;
  color: var(--ink-faint);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.summary-metrics {
  display: flex;
  gap: 8px;
  margin-left: 12px;
  flex-wrap: wrap;
}

.metric-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  color: var(--ink-soft);
  font-size: 11px;
  background: rgba(15, 108, 189, 0.06);
  border-radius: 8px;
}

.metric-chip.is-muted {
  color: var(--ink-faint);
  background: rgba(0, 0, 0, 0.04);
}

.row-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-left: 8px;
}

.library-expanded {
  padding: 0 20px 20px;
  background: rgba(15, 108, 189, 0.018);
}

.expanded-inner {
  padding-top: 16px;
  border-top: 1px dashed var(--line-strong);
}

.rule-editor-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.rule-editor-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}

.rule-editor-title strong {
  color: var(--ink);
  font-size: 15px;
}

.rule-editor-title span {
  color: var(--ink-faint);
  font-size: 12px;
}

.rule-editor-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.save-state {
  color: var(--ink-faint);
  font-size: 12px;
}

.save-state.is-dirty {
  color: #a26718;
}

.rule-hint {
  margin: 10px 0 12px;
  color: var(--ink-faint);
  font-size: 12px;
  line-height: 1.6;
}

.validation-alert {
  margin: 10px 0 12px;
}

.validation-issues {
  max-height: 150px;
  margin: 8px 0 0;
  padding-left: 18px;
  overflow: auto;
  line-height: 1.55;
}

.structured-definition,
.legacy-definition {
  padding: 12px 14px 14px;
  background: #fbfdfc;
}

.legacy-definition {
  display: grid;
  gap: 8px;
  color: var(--ink-faint);
  font-size: 12px;
}

.legacy-definition :deep(.el-textarea__inner) {
  color: var(--ink-faint);
  font-family: 'Cascadia Code', 'SFMono-Regular', Consolas, monospace;
  font-size: 12px;
}

.config-row,
.condition-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.condition-row:last-child,
.config-row:last-child {
  margin-bottom: 0;
}

.config-control {
  min-width: 160px;
  max-width: 340px;
}

.config-table,
.config-multi {
  min-width: min(340px, 100%);
  flex: 1 1 240px;
}

.config-field {
  min-width: 180px;
  flex: 1 1 200px;
}

.config-operator,
.config-operation,
.config-effect {
  min-width: 150px;
}

.config-value {
  flex: 1 1 150px;
}

.definition-label {
  width: 58px;
  flex: 0 0 58px;
  margin-top: 0;
  text-align: left;
}

.config-note {
  overflow: hidden;
  color: var(--ink-faint);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.input-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  min-width: 0;
  width: 100%;
}

.input-option strong,
.input-option small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.input-option strong {
  min-width: 0;
  font-weight: 500;
}

.input-option small {
  max-width: 42%;
  flex: 0 1 auto;
  color: var(--ink-faint);
}

.add-condition {
  margin-left: 66px;
  margin-bottom: 10px;
}

.derive-basics {
  display: grid;
  gap: 2px;
  margin-bottom: 10px;
}

.derive-basic-row {
  align-items: flex-start;
}

.derive-field-name,
.derive-output-alias {
  min-width: 220px;
  flex: 1 1 260px;
}

.derive-data-type {
  width: 150px;
  min-width: 150px;
  flex: 0 0 150px;
}

.derive-expression-row {
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr);
  align-items: start;
  gap: 8px;
  min-width: 0;
}

.derive-expression-editor {
  min-width: 0;
}

.aggregate-group-row .config-multi {
  min-width: min(360px, 100%);
}

.aggregate-output-alias {
  flex: 0 1 220px;
}

.aggregate-editor {
  min-width: 0;
  margin: 4px 0 12px 66px;
  padding-top: 10px;
  border-top: 1px solid var(--line);
}

.join-input-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  min-width: 0;
  margin: 2px 0 12px 66px;
}

.join-input-panel {
  display: grid;
  min-width: 0;
  gap: 7px;
  padding: 11px;
  background: #f8fbfa;
  border: 1px solid var(--line);
  border-radius: 8px;
}

.join-input-label,
.join-setting > span,
.join-key-head > span {
  color: var(--ink-soft);
  font-size: 11px;
  font-weight: 600;
}

.join-input-panel > small,
.join-key-head > small {
  overflow: hidden;
  color: var(--ink-faint);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.join-input-select {
  width: 100%;
  min-width: 0;
}

.join-key-editor {
  min-width: 0;
  margin: 0 0 12px 66px;
  padding: 10px 0;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}

.join-key-head {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.join-key-head .el-button {
  margin-left: auto;
}

.join-key-empty {
  padding: 12px;
  color: var(--ink-faint);
  font-size: 12px;
  text-align: center;
  background: #fbfdfc;
  border: 1px dashed var(--line-strong);
  border-radius: 6px;
}

.join-key-row {
  display: grid;
  grid-template-columns: 26px minmax(160px, 1fr) 20px minmax(160px, 1fr) 44px;
  align-items: center;
  gap: 8px;
  min-width: 0;
  margin-bottom: 8px;
}

.join-key-row .el-select {
  width: 100%;
  min-width: 0;
}

.join-key-equals {
  color: var(--ink-faint);
  text-align: center;
}

.join-settings-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(150px, 1fr));
  gap: 10px;
  min-width: 0;
  margin: 0 0 12px 66px;
}

.join-setting {
  display: grid;
  min-width: 0;
  gap: 6px;
}

.join-setting .el-select,
.join-setting .el-input-number {
  width: 100%;
  min-width: 0;
}

.join-switch-setting {
  align-content: start;
}

.join-output-row {
  align-items: center;
}

.aggregate-editor-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  color: var(--ink-faint);
  font-size: 11px;
  flex-wrap: wrap;
}

.aggregate-editor-head .definition-label {
  width: auto;
  flex: 0 0 auto;
  text-align: left;
}

.aggregate-editor-head .el-button {
  margin-left: auto;
}

.aggregate-metric-row {
  display: grid;
  grid-template-columns: 26px minmax(150px, 1fr) minmax(125px, 160px) minmax(160px, 1.2fr) 44px;
  align-items: center;
  gap: 8px;
  min-width: 0;
  margin-bottom: 8px;
}

.metric-order {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  color: var(--ink-faint);
  font-size: 11px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 50%;
}

.metric-name-control,
.metric-operation-control,
.metric-field-control {
  width: 100%;
  min-width: 0;
}

.field-option {
  display: block;
  overflow: hidden;
  min-width: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-toolbar,
.preview-history-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex-wrap: wrap;
}

.preview-toolbar {
  padding-bottom: 12px;
  border-bottom: 1px solid var(--line);
}

.preview-toolbar-note,
.preview-history-row > span {
  color: var(--ink-faint);
  font-size: 12px;
}

.preview-history-row {
  margin: 12px 0;
}

.preview-history-select {
  width: min(360px, 100%);
}

.preview-run-alert {
  margin-bottom: 8px;
}

.preview-run-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
  margin-bottom: 14px;
}

.preview-run-meta > span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: 100%;
  min-width: 0;
  padding: 0 9px 0 0;
  color: var(--ink-soft);
  font-size: 12px;
  line-height: 1.35;
  border-right: 1px solid var(--line);
}

.preview-run-meta > span.is-expired {
  color: var(--el-color-danger);
}

.preview-run-meta > .preview-run-actions {
  gap: 6px;
  margin-left: auto;
  padding: 0;
  border: 0;
}

.preview-worker strong {
  overflow: hidden;
  max-width: min(320px, 48vw);
  min-width: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-layout {
  display: grid;
  grid-template-columns: minmax(190px, 245px) minmax(0, 1fr);
  gap: 14px;
  min-height: 360px;
}

.preview-node-list {
  display: flex;
  max-height: 68vh;
  flex-direction: column;
  gap: 5px;
  overflow: auto;
  padding-right: 4px;
}

.preview-node-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  min-width: 0;
  padding: 9px 8px;
  color: var(--ink);
  text-align: left;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 6px;
  cursor: pointer;
}

.preview-node-item.is-active {
  background: rgba(15, 108, 189, 0.06);
  border-color: rgba(15, 108, 189, 0.34);
}

.preview-node-main {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.preview-node-main strong,
.preview-result-head strong,
.preview-result-head span {
  overflow: hidden;
  min-width: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-node-main strong {
  font-size: 12px;
  font-weight: 600;
}

.preview-node-main small {
  color: var(--ink-faint);
  font-size: 11px;
}

.preview-result-panel {
  min-width: 0;
}

.preview-result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
  margin-bottom: 12px;
}

.preview-result-head > div {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.preview-result-head strong {
  font-size: 14px;
}

.preview-result-head span {
  color: var(--ink-faint);
  font-size: 11px;
}

.preview-metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(115px, 1fr));
  gap: 8px;
  margin: 10px 0 14px;
}

.preview-metric-card {
  display: grid;
  min-width: 0;
  gap: 5px;
  padding: 10px;
  background: #f7faf9;
  border: 1px solid var(--line);
  border-radius: 6px;
}

.preview-metric-card span {
  color: var(--ink-faint);
  font-size: 11px;
}

.preview-metric-card strong {
  overflow-wrap: anywhere;
  font-size: 14px;
}

.preview-table-wrap {
  min-width: 0;
  overflow: hidden;
}

:global(.preview-drawer .el-drawer__body) {
  min-width: 0;
  overflow: auto;
}

.dialog-copy {
  margin: 0 0 16px;
  color: var(--ink-faint);
  font-size: 13px;
  line-height: 1.6;
}

.dialog-select {
  width: 100%;
}

.dataset-option {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.dataset-option strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dataset-option small {
  flex: 0 0 auto;
  color: var(--ink-faint);
}

.rule-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 0 12px;
  flex-wrap: wrap;
}

.rule-search-input {
  width: 280px;
}

.rule-filter-count {
  color: var(--ink-faint);
  font-size: 12px;
}

.rule-filter-count strong {
  color: var(--ink-soft);
}

.rule-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.rule-item {
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-panel);
  transition: border-color 0.15s ease;
}

.rule-item:hover {
  border-color: rgba(15, 108, 189, 0.24);
}

.rule-head {
  display: grid;
  grid-template-columns: 30px minmax(180px, 1fr) 170px auto auto;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  background: rgba(15, 108, 189, 0.045);
  border-bottom: 1px dashed var(--line-strong);
}

.rule-index {
  display: inline-grid;
  width: 26px;
  height: 26px;
  place-items: center;
  color: var(--accent);
  font-size: 11px;
  font-weight: 700;
  background: rgba(15, 108, 189, 0.1);
  border-radius: var(--radius-control);
}
.rule-name-input {
  min-width: 0;
}

.rule-template-select {
  width: 170px;
}

.template-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.template-option strong {
  color: var(--ink);
  font-weight: 600;
}

.template-option small {
  color: var(--ink-faint);
  font-size: 11px;
}

.rule-enabled {
  justify-self: center;
}

.rule-delete {
  justify-self: end;
}

.rule-definition {
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr);
  align-items: start;
  gap: 10px;
  padding: 12px 14px 14px;
}

.definition-label {
  margin-top: 5px;
  color: var(--ink-faint);
  font-size: 11px;
  text-align: center;
}

.rule-definition :deep(.el-textarea__inner) {
  min-height: 58px !important;
  color: var(--ink);
  font-family: 'Cascadia Code', 'SFMono-Regular', Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.65;
  background: #fbfdfc;
}

.rule-add-row {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  gap: 8px;
  margin-top: 10px;
  padding: 14px 16px;
  color: var(--accent);
  font-size: 13px;
  font-weight: 600;
  background: rgba(15, 108, 189, 0.035);
  border: 1px dashed rgba(15, 108, 189, 0.32);
  border-radius: var(--radius-control);
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.rule-add-row:hover {
  background: rgba(15, 108, 189, 0.075);
  border-color: rgba(15, 108, 189, 0.5);
}

.rule-add-row small {
  color: var(--ink-faint);
  font-size: 11px;
  font-weight: 400;
}

.rule-empty {
  padding: 18px 0;
}

.rule-pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 14px;
  margin-top: 16px;
  flex-wrap: wrap;
}

.pagination-hint {
  color: var(--ink-faint);
  font-size: 12px;
}

.library-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
  flex-wrap: wrap;
}

.empty-state {
  padding: 26px 0;
}

@media (max-width: 900px) {
  .rule-head {
    grid-template-columns: 30px minmax(0, 1fr);
  }

  .rule-name-input,
  .rule-template-select,
  .rule-enabled,
  .rule-delete {
    grid-column: 2;
    justify-self: start;
  }

  .rule-template-select {
    width: 100%;
  }

  .config-control,
  .config-table,
  .config-multi {
    width: 100%;
    max-width: none;
  }

  .config-note {
    width: 100%;
    margin-left: 66px;
  }

  .derive-expression-row {
    grid-template-columns: minmax(0, 1fr);
  }

  .derive-expression-row > .definition-label {
    width: auto;
    text-align: left;
  }

  .derive-data-type {
    width: 100%;
    max-width: none;
    flex-basis: auto;
  }

  .aggregate-metric-row {
    grid-template-columns: 24px minmax(0, 1fr) minmax(0, 1fr);
  }

  .metric-name-control,
  .metric-operation-control,
  .metric-field-control,
  .aggregate-metric-row > .el-button {
    grid-column: 2 / -1;
    width: 100%;
  }

  .aggregate-editor {
    margin-left: 0;
  }

  .join-input-grid,
  .join-key-editor,
  .join-settings-grid {
    margin-left: 0;
  }

  .join-settings-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .join-key-row {
    grid-template-columns: 24px minmax(0, 1fr) 20px minmax(0, 1fr);
  }

  .join-key-row > .el-button {
    grid-column: 2 / -1;
    width: 100%;
  }

  .aggregate-editor-head .definition-label {
    margin-left: 0;
  }

  .library-summary {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .summary-metrics {
    margin-left: 32px;
  }

  .row-actions {
    margin-left: auto;
  }
}

@media (max-width: 640px) {
  .join-input-grid,
  .join-settings-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .join-key-row {
    grid-template-columns: 24px minmax(0, 1fr);
  }

  .join-key-equals {
    display: none;
  }

  .join-key-row > .el-select,
  .join-key-row > .el-button {
    grid-column: 2;
  }

  .preview-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .preview-node-list {
    display: grid;
    max-height: 190px;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .preview-node-item {
    align-items: flex-start;
  }

  .preview-toolbar .el-input-number {
    width: 140px;
  }

  .preview-toolbar-note {
    flex: 1 1 180px;
  }

  .preview-history-select {
    width: 100%;
  }
}
</style>
