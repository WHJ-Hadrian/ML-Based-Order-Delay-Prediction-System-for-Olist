<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  CopyDocument,
  Delete,
  DocumentAdd,
  Plus,
  Refresh,
  Setting,
  Upload,
  VideoPlay,
  View,
} from '@element-plus/icons-vue'
import { AuthenticationRequiredError } from '@/auth/keycloak'
import { getDataset, listDatasets } from '@/api/datasets'
import {
  createRuleRun,
  createRuleWorkbook,
  deleteRuleWorkbook,
  getFieldCatalog,
  getRuleRun,
  getRuleRunExecution,
  getRuleWorkbook,
  listRuleRunNodes,
  listRuleRuns,
  listRuleWorkbooks,
  listRuleWorkbookVersions,
  publishRuleWorkbook,
  saveRuleWorkbookDraft,
  validateRuleWorkbook,
} from '@/api/ruleLibrary'

const effectOptions = ['OBSERVE', 'WARN', 'FAIL_RUN', 'BLOCK_PUBLISH', 'QUARANTINE_ROWS', 'DROP_ROWS', 'FLAG_ROWS']
const rowEffects = new Set(['QUARANTINE_ROWS', 'DROP_ROWS', 'FLAG_ROWS'])
const operationOptions = [
  'TRIM', 'LOWER', 'UPPER', 'CAST', 'REPLACE', 'REGEX_REPLACE', 'MAP_VALUES',
  'FILL_VALUE', 'COALESCE', 'DATE_PARSE', 'CLIP_OUTLIER', 'MASK', 'SPLIT_COLUMN', 'MERGE_COLUMNS',
]
const joinTypeOptions = ['INNER', 'LEFT', 'RIGHT', 'FULL']
const cardinalityOptions = ['1:1', '1:N', 'N:1', 'N:N']
const dataTypeOptions = ['STRING', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'DATETIME']
const nodeKindOptions = ['CHECK', 'TRANSFORM', 'PROJECT', 'FILTER', 'DEDUPE', 'JOIN', 'AGGREGATE', 'DERIVE', 'GATE', 'OUTPUT']
const predicateOps = {
  IS_NULL: '为空',
  IS_EMPTY: '为空字符串',
  IS_BLANK: '为空白',
  EQ: '等于',
  NE: '不等于',
  GT: '大于',
  GTE: '大于等于',
  LT: '小于',
  LTE: '小于等于',
  BETWEEN: '区间内',
  IN: '属于集合',
  NOT_IN: '不属于集合',
  REGEX_MATCH: '匹配正则',
  DATE_PARSEABLE: '可解析为日期',
  DATE_BEFORE: '早于日期',
  DATE_AFTER: '晚于日期',
  DATE_ORDER_INVALID: '日期顺序错误',
  DUPLICATE: '字段组合重复',
  UNIQUE: '字段组合唯一',
  DUPLICATE_RATE_GT: '重复率大于',
  FK_EXISTS: '外键存在',
  JOIN_MATCH_RATE_LT: '关联匹配率小于',
  JOIN_FANOUT_GT: '关联膨胀倍数大于',
  MISSING_RATE_GT: '缺失率大于',
  ROW_COUNT_LT: '行数小于',
  CATEGORY_RATIO_LT: '类目占比小于',
  DISTINCT_COUNT_LT: '去重数小于',
  AND: '同时满足（JSON）',
  OR: '任一满足（JSON）',
  NOT: '条件取反（JSON）',
}
const customColumnTypes = ['TEXT', 'NUMBER', 'BOOLEAN', 'ENUM', 'DATE_VALUE', 'TAGS']
const thresholdPredicateOps = new Set([
  'DUPLICATE_RATE_GT', 'JOIN_MATCH_RATE_LT', 'JOIN_FANOUT_GT',
  'MISSING_RATE_GT', 'ROW_COUNT_LT', 'CATEGORY_RATIO_LT', 'DISTINCT_COUNT_LT',
])
const noFieldPredicateOps = new Set(['ROW_COUNT_LT'])
const fieldListPredicateOps = new Set(['DUPLICATE', 'UNIQUE', 'DUPLICATE_RATE_GT'])
const valuePredicateOps = new Set([
  'EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'REGEX_MATCH', 'IN', 'NOT_IN',
  'DATE_BEFORE', 'DATE_AFTER', 'CATEGORY_RATIO_LT',
])
const compositePredicateOps = new Set(['AND', 'OR', 'NOT'])

const datasets = ref([])
const workbooks = ref([])
const loadingDatasets = ref(false)
const loadingWorkbooks = ref(false)
const creating = ref(false)
const selectedDatasetId = ref('')
const newWorkbookName = ref('')
const workbookKeyword = ref('')
const activeWorkbookId = ref('')
const activeWorkbook = ref(null)
const activeSheetKey = ref('')
const datasetDetail = ref(null)
const fieldCatalog = ref([])
const versions = ref([])
const runs = ref([])
const runHistoryVisible = ref(false)
const runDetailVisible = ref(false)
const selectedRun = ref(null)
const runExecution = ref(null)
const runNodes = ref([])
const loadingRun = ref(false)
const refreshingRuns = ref(false)
const runPollTimer = ref(null)
const loadingEditor = ref(false)
const saving = ref(false)
const validating = ref(false)
const publishing = ref(false)
const running = ref(false)
const dirty = ref(false)
const validationResult = ref(null)
const columnDialogVisible = ref(false)
const customColumn = reactive({ title: '', cellType: 'TEXT' })
const predicateDialog = reactive({
  visible: false,
  row: null,
  column: null,
  op: 'IS_NULL',
  fieldColumnId: '',
  secondColumnId: '',
  fieldColumnIds: [],
  value: '',
  secondValue: '',
  threshold: null,
  jsonMode: false,
  jsonText: '{}',
})
const paramDialog = reactive({ visible: false, row: null, column: null, text: '{}' })

let suspendDirtyWatch = false

const activeSheet = computed(() => {
  const sheets = activeWorkbook.value?.document?.sheets ?? []
  return sheets.find((sheet) => sheet.key === activeSheetKey.value) || sheets[0] || null
})

const visibleColumns = computed(
  () => (activeSheet.value?.columns ?? [])
    .filter((column) => !column.hidden && column.key !== 'enabled'),
)

const tableOptions = computed(() => datasetDetail.value?.tables ?? [])

const selectableDatasets = computed(
  () => datasets.value.filter((dataset) => dataset.status === 'READY'),
)

const datasetName = computed(() => {
  const id = activeWorkbook.value?.datasetVersionId
  return datasets.value.find((dataset) => dataset.id === id)?.name
    || datasetDetail.value?.name
    || '未知数据集'
})

const latestVersion = computed(() => versions.value[0] || null)
const latestRun = computed(() => runs.value[0] || null)

const runTerminalStatuses = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED'])
const runIsLive = computed(() => selectedRun.value && !runTerminalStatuses.has(selectedRun.value.status))

const runNodeRows = computed(() => {
  const reports = new Map(runNodes.value.map((node) => [node.nodeId, node]))
  const planNodes = runExecution.value?.compiledPlan?.nodes
  if (Array.isArray(planNodes) && planNodes.length) {
    return planNodes.map((node) => {
      const report = reports.get(node.id)
      return {
        nodeId: node.id,
        nodeKind: node.kind,
        name: node.name,
        stage: node.stage,
        status: report?.status || 'PENDING',
        metrics: report?.metrics || null,
        sampleUri: report?.sampleUri || '',
        errorMessage: report?.errorMessage || '',
        startedAt: report?.startedAt || null,
        finishedAt: report?.finishedAt || null,
      }
    })
  }
  return runNodes.value.map((node) => ({ ...node, name: node.nodeId }))
})

const nodeOutputs = computed(() => {
  const result = []
  for (const sheet of activeWorkbook.value?.document?.sheets ?? []) {
    for (const row of sheet.rows ?? []) {
      if (row.enabled === false) {
        continue
      }
      const alias = row.cells?.outputAlias
      if (alias) {
        result.push({
          nodeId: `${sheet.key}:${row.id}`,
          outputAlias: alias,
          label: `${alias} · ${sheet.title}`,
        })
      }
    }
  }
  return result
})

watch(
  () => activeWorkbook.value?.document,
  () => {
    if (!suspendDirtyWatch && activeWorkbook.value) {
      dirty.value = true
    }
  },
  { deep: true },
)

function handleAuthError(error) {
  if (error instanceof AuthenticationRequiredError) {
    return true
  }
  return false
}

async function loadDatasets() {
  loadingDatasets.value = true
  try {
    const result = await listDatasets({ page: 0, size: 100, hasData: true })
    datasets.value = (result?.items ?? []).filter((item) => item.tableCount > 0)
    const ready = selectableDatasets.value
    if (!ready.some((item) => item.id === selectedDatasetId.value)) {
      selectedDatasetId.value = ready[0]?.id || ''
    }
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
      keyword: workbookKeyword.value,
    })
    workbooks.value = result?.items ?? []
  } catch (error) {
    if (!handleAuthError(error)) {
      ElMessage.error(error?.message || '加载规则库失败')
    }
  } finally {
    loadingWorkbooks.value = false
  }
}

async function createWorkbook() {
  if (!selectedDatasetId.value) {
    ElMessage.warning('请先选择数据集')
    return
  }
  const name = newWorkbookName.value.trim()
  if (!name) {
    ElMessage.warning('请填写规则库名称')
    return
  }
  creating.value = true
  try {
    const created = await createRuleWorkbook({
      name,
      datasetVersionId: selectedDatasetId.value,
    })
    newWorkbookName.value = ''
    await loadWorkbooks()
    await openWorkbook(created)
    ElMessage.success(`已创建「${created.name}」`)
  } catch (error) {
    if (!handleAuthError(error)) {
      ElMessage.error(error?.message || '创建规则库失败')
    }
  } finally {
    creating.value = false
  }
}

async function confirmDiscardChanges() {
  if (!dirty.value) {
    return true
  }
  try {
    await ElMessageBox.confirm('当前草稿还有未保存修改，确定放弃吗？', '未保存修改', {
      confirmButtonText: '放弃修改',
      cancelButtonText: '继续编辑',
      type: 'warning',
    })
    return true
  } catch {
    return false
  }
}

async function toggleWorkbook(workbook) {
  if (activeWorkbookId.value === workbook.id) {
    if (await confirmDiscardChanges()) {
      closeEditor()
    }
    return
  }
  if (!(await confirmDiscardChanges())) {
    return
  }
  await openWorkbook(workbook)
}

function closeEditor() {
  runHistoryVisible.value = false
  runDetailVisible.value = false
  resetRunDetail()
  activeWorkbookId.value = ''
  activeWorkbook.value = null
  activeSheetKey.value = ''
  datasetDetail.value = null
  fieldCatalog.value = []
  versions.value = []
  runs.value = []
  validationResult.value = null
  dirty.value = false
}

async function openWorkbook(workbook) {
  runHistoryVisible.value = false
  runDetailVisible.value = false
  resetRunDetail()
  loadingEditor.value = true
  suspendDirtyWatch = true
  try {
    const detail = await getRuleWorkbook(workbook.id)
    normalizeDocumentRows(detail.document)
    const [dataset, catalog, versionRows, runRows] = await Promise.all([
      getDataset(detail.datasetVersionId),
      getFieldCatalog(detail.datasetVersionId).catch(() => []),
      listRuleWorkbookVersions(detail.id).catch(() => []),
      listRuleRuns(detail.id).catch(() => []),
    ])
    activeWorkbookId.value = detail.id
    activeWorkbook.value = detail
    datasetDetail.value = dataset
    fieldCatalog.value = catalog ?? []
    versions.value = versionRows ?? []
    runs.value = runRows ?? []
    activeSheetKey.value = detail.document?.sheets?.[0]?.key || ''
    validationResult.value = null
    dirty.value = false
    await nextTick()
  } catch (error) {
    if (!handleAuthError(error)) {
      ElMessage.error(error?.message || '打开规则库失败')
    }
    closeEditor()
  } finally {
    suspendDirtyWatch = false
    loadingEditor.value = false
  }
}

async function refreshRuns({ silent = false } = {}) {
  if (!activeWorkbook.value) {
    return
  }
  refreshingRuns.value = true
  try {
    runs.value = (await listRuleRuns(activeWorkbook.value.id)) ?? []
  } catch (error) {
    if (!silent && !handleAuthError(error)) {
      ElMessage.error(error?.message || '加载运行记录失败')
    }
  } finally {
    refreshingRuns.value = false
  }
}

function stopRunPolling() {
  if (runPollTimer.value) {
    window.clearInterval(runPollTimer.value)
    runPollTimer.value = null
  }
}

function scheduleRunPolling() {
  stopRunPolling()
  if (!runDetailVisible.value || !selectedRun.value || runTerminalStatuses.has(selectedRun.value.status)) {
    return
  }
  runPollTimer.value = window.setInterval(() => loadRunDetail(selectedRun.value, { silent: true }), 3000)
}

async function loadRunDetail(run, { silent = false } = {}) {
  const runId = run?.id
  if (!runId) {
    return
  }
  if (!silent) {
    loadingRun.value = true
  }
  try {
    const [fresh, execution, nodes] = await Promise.all([
      getRuleRun(runId),
      getRuleRunExecution(runId).catch(() => null),
      listRuleRunNodes(runId).catch(() => []),
    ])
    if (selectedRun.value?.id !== runId) {
      return
    }
    selectedRun.value = fresh
    runExecution.value = execution
    runNodes.value = nodes ?? []
    runs.value = runs.value.map((item) => (item.id === fresh.id ? fresh : item))
    if (runTerminalStatuses.has(fresh.status)) {
      stopRunPolling()
    }
  } catch (error) {
    stopRunPolling()
    if (!silent && !handleAuthError(error)) {
      ElMessage.error(error?.message || '加载运行详情失败')
    }
  } finally {
    if (!silent) {
      loadingRun.value = false
    }
  }
}

async function openRunDetail(run) {
  runDetailVisible.value = true
  selectedRun.value = run
  runExecution.value = null
  runNodes.value = []
  await loadRunDetail(run)
  scheduleRunPolling()
}

async function openRunHistory() {
  runHistoryVisible.value = true
  await refreshRuns()
}

function viewRunFromHistory(run) {
  runHistoryVisible.value = false
  openRunDetail(run)
}

function closeRunDetail() {
  runDetailVisible.value = false
  stopRunPolling()
}

function resetRunDetail() {
  stopRunPolling()
  selectedRun.value = null
  runExecution.value = null
  runNodes.value = []
}

async function removeWorkbook(workbook) {
  try {
    await ElMessageBox.confirm(`确定删除规则库「${workbook.name}」及其所有版本吗？`, '删除规则库', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await deleteRuleWorkbook(workbook.id)
    if (activeWorkbookId.value === workbook.id) {
      closeEditor()
    }
    await loadWorkbooks()
    ElMessage.success('规则库已删除')
  } catch (error) {
    if (!handleAuthError(error)) {
      ElMessage.error(error?.message || '删除规则库失败')
    }
  }
}

async function persistDraft({ silent = false } = {}) {
  if (!activeWorkbook.value) {
    return null
  }
  saving.value = true
  try {
    const saved = await saveRuleWorkbookDraft(
      activeWorkbook.value.id,
      activeWorkbook.value.document,
      activeWorkbook.value.revision,
    )
    suspendDirtyWatch = true
    activeWorkbook.value = saved
    await nextTick()
    suspendDirtyWatch = false
    dirty.value = false
    await loadWorkbooks()
    if (!silent) {
      ElMessage.success('草稿已保存')
    }
    return saved
  } catch (error) {
    if (!handleAuthError(error)) {
      ElMessage.error(error?.message || '保存草稿失败')
    }
    return null
  } finally {
    saving.value = false
  }
}

async function performValidation({ silent = false } = {}) {
  const saved = await persistDraft({ silent: true })
  if (!saved) {
    return null
  }
  validating.value = true
  try {
    const result = await validateRuleWorkbook(saved.id)
    validationResult.value = result
    if (activeWorkbook.value) {
      activeWorkbook.value.status = result.valid ? 'VALIDATED' : 'DRAFT'
    }
    await loadWorkbooks()
    if (!silent) {
      if (result.valid) {
        ElMessage.success(`校验通过，共 ${result.nodeCount} 个规则节点`)
      } else {
        ElMessage.warning(`校验未通过，共 ${result.issues?.length || 0} 个问题`)
      }
    }
    return result
  } catch (error) {
    if (!handleAuthError(error)) {
      ElMessage.error(error?.message || '校验规则库失败')
    }
    return null
  } finally {
    validating.value = false
  }
}

async function validateCurrent() {
  await performValidation()
}

async function publishCurrent() {
  if (!activeWorkbook.value || publishing.value) {
    return
  }
  publishing.value = true
  try {
    const result = await performValidation({ silent: true })
    if (!result?.valid) {
      ElMessage.warning('请先修复校验问题再发布')
      return
    }
    const version = await publishRuleWorkbook(activeWorkbook.value.id)
    versions.value = await listRuleWorkbookVersions(activeWorkbook.value.id)
    activeWorkbook.value.status = 'PUBLISHED'
    await loadWorkbooks()
    ElMessage.success(`已发布 v${version.versionNumber}`)
  } catch (error) {
    if (!handleAuthError(error)) {
      ElMessage.error(error?.message || '发布失败')
    }
  } finally {
    publishing.value = false
  }
}

async function runPreview() {
  if (!activeWorkbook.value || running.value) {
    return
  }
  running.value = true
  try {
    const run = await createRuleRun(activeWorkbook.value.id, {
      mode: 'PREVIEW',
      sampleSize: 1000,
    })
    runs.value = [run, ...runs.value]
    ElMessage.success('预览运行已排队，等待 Worker 处理')
    await openRunDetail(run)
  } catch (error) {
    if (!handleAuthError(error)) {
      ElMessage.error(error?.message || '创建运行失败')
    }
  } finally {
    running.value = false
  }
}

function addRow() {
  const sheet = activeSheet.value
  if (!sheet) {
    return
  }
  if (!Array.isArray(sheet.rows)) {
    sheet.rows = []
  }
  sheet.rows.push({
    id: `row_${Date.now()}_${sheet.rows.length + 1}`,
    enabled: true,
    cells: {},
  })
}

function duplicateRow(row) {
  const sheet = activeSheet.value
  if (!sheet) {
    return
  }
  const copy = JSON.parse(JSON.stringify(row))
  copy.id = `row_${Date.now()}_${sheet.rows.length + 1}`
  sheet.rows.push(copy)
}

async function removeRow(row) {
  const sheet = activeSheet.value
  if (!sheet) {
    return
  }
  try {
    await ElMessageBox.confirm('确定删除这条规则吗？', '删除规则', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  sheet.rows = sheet.rows.filter((item) => item.id !== row.id)
}

function ensureCells(row) {
  if (!row.cells || typeof row.cells !== 'object') {
    row.cells = {}
  }
  return row.cells
}

function normalizeDocumentRows(document) {
  for (const sheet of document?.sheets ?? []) {
    if (!Array.isArray(sheet.rows)) {
      sheet.rows = []
    }
    for (const row of sheet.rows) {
      if (!row.cells || typeof row.cells !== 'object') {
        row.cells = {}
      }
      if (row.enabled == null) {
        row.enabled = true
      }
      if (!row.id) {
        row.id = `row_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
      }
    }
  }
}

function setCell(row, column, value) {
  if (column.key === 'enabled') {
    row.enabled = Boolean(value)
    return
  }
  const cells = ensureCells(row)
  cells[column.key] = value
  if (column.key === 'effect' && rowEffects.has(value) && !cells.outputAlias) {
    cells.outputAlias = `checked_${row.id || Date.now()}`
  }
  if (column.cellType === 'TABLE_REF' || column.cellType === 'INPUT_REF') {
    for (const dependent of activeSheet.value?.columns ?? []) {
      if (Array.isArray(dependent.dependsOn) && dependent.dependsOn.includes(column.key)) {
        delete cells[dependent.key]
      }
    }
  }
}

function tableRefId(value) {
  if (!value) {
    return ''
  }
  if (typeof value === 'string') {
    return value
  }
  return value.tableId || ''
}

function tableName(id) {
  return tableOptions.value.find((table) => table.id === id)?.name || ''
}

function tableRefModel(row, column) {
  return tableRefId(row.cells?.[column.key])
}

function setTableRef(row, column, tableId) {
  setCell(row, column, tableId ? { type: 'TABLE', tableId } : null)
}

function inputRefModel(value) {
  if (!value) {
    return ''
  }
  if (value.type === 'NODE_OUTPUT' || (!value.tableId && value.outputAlias)) {
    return `NODE:${value.outputAlias}`
  }
  return value.tableId ? `TABLE:${value.tableId}` : ''
}

function setInputRef(row, column, refKey) {
  if (!refKey) {
    setCell(row, column, null)
    return
  }
  if (refKey.startsWith('TABLE:')) {
    setCell(row, column, { type: 'TABLE', tableId: refKey.slice('TABLE:'.length) })
    return
  }
  if (refKey.startsWith('NODE:')) {
    const outputAlias = refKey.slice('NODE:'.length)
    const output = nodeOutputs.value.find((item) => item.outputAlias === outputAlias)
    setCell(row, column, output
      ? { type: 'NODE_OUTPUT', nodeId: output.nodeId, outputAlias }
      : null)
  }
}

function parentTableId(row, column) {
  const dependencies = Array.isArray(column.dependsOn) ? column.dependsOn : []
  for (const key of dependencies) {
    const tableId = tableRefId(row.cells?.[key])
    if (tableId) {
      return tableId
    }
  }
  for (const key of ['table', 'input', 'leftInput', 'rightInput']) {
    const tableId = tableRefId(row.cells?.[key])
    if (tableId) {
      return tableId
    }
  }
  return ''
}

function fieldOptionsFor(row, column) {
  const tableId = parentTableId(row, column)
  return fieldCatalog.value.filter((field) => !tableId || field.tableId === tableId)
}

function fieldOptionLabel(option) {
  return option.fieldName ? `${option.columnName} · ${option.fieldName}` : option.columnName
}

function fieldRefModelId(value, options) {
  if (!value) {
    return ''
  }
  const columnId = typeof value === 'string' ? value : value.columnId
  const fieldId = typeof value === 'object' ? value.fieldId : value
  const byColumn = options.find((option) => option.columnId === columnId)
  if (byColumn) {
    return byColumn.columnId
  }
  const byField = options.find((option) => option.fieldId === fieldId)
  if (byField) {
    return byField.columnId
  }
  const byId = options.find((option) => option.columnId === fieldId)
  return byId?.columnId || columnId || fieldId || ''
}

function toFieldRef(option) {
  if (!option) {
    return null
  }
  const ref = {
    type: 'FIELD',
    columnId: option.columnId,
    nameSnapshot: option.columnName,
    tableId: option.tableId,
  }
  if (option.fieldId) {
    ref.fieldId = option.fieldId
  }
  return ref
}

function fieldRefModelIdFor(row, column) {
  return fieldRefModelId(row.cells?.[column.key], fieldOptionsFor(row, column))
}

function setFieldRef(row, column, columnId) {
  const option = fieldOptionsFor(row, column).find((field) => field.columnId === columnId)
  setCell(row, column, toFieldRef(option))
}

function fieldListModel(row, column) {
  const value = row.cells?.[column.key]
  if (!Array.isArray(value)) {
    return []
  }
  const options = fieldOptionsFor(row, column)
  return value.map((item) => fieldRefModelId(item, options)).filter(Boolean)
}

function setFieldListRef(row, column, columnIds) {
  const options = fieldOptionsFor(row, column)
  const refs = (columnIds ?? [])
    .map((columnId) => options.find((option) => option.columnId === columnId))
    .filter(Boolean)
    .map(toFieldRef)
  setCell(row, column, refs)
}

function nodeOutputModel(value) {
  if (!value) {
    return ''
  }
  return value.outputAlias || value.nodeId || ''
}

function setNodeOutputRef(row, column, outputAlias) {
  const output = nodeOutputs.value.find((item) => item.outputAlias === outputAlias)
  setCell(row, column, output ? { type: 'NODE_OUTPUT', nodeId: output.nodeId, outputAlias } : null)
}

function enumOptions(column) {
  const key = String(column.key || '').toLowerCase()
  if (column.cellType === 'NODE_KIND') return nodeKindOptions
  if (key === 'effect') return effectOptions
  if (key === 'operation') return operationOptions
  if (key === 'jointype') return joinTypeOptions
  if (key === 'cardinality') return cardinalityOptions
  if (key === 'datatype') return dataTypeOptions
  if (key === 'blocking') return ['NONE', 'WARN', 'BLOCK_PUBLISH']
  if (key === 'scope') return ['ROW', 'FIELD_AGGREGATE', 'RELATION_AGGREGATE', 'DATASET_AGGREGATE']
  if (key === 'keep') return ['FIRST', 'LAST']
  if (key === 'comparator') return ['GT', 'GTE', 'LT', 'LTE', 'EQ', 'NE']
  if (key === 'onerror') return ['FAIL_RUN', 'SKIP_NODE', 'KEEP_ORIGINAL', 'QUARANTINE_ROWS']
  if (column.key === 'mode') return ['MATERIALIZE_VERSION', 'EXPORT', 'QUARANTINE']
  if (column.key === 'format') return ['PARQUET', 'CSV', 'JSONL', 'XLSX']
  return column.config?.options ?? []
}

function predicateSummary(value) {
  if (!value || typeof value !== 'object') {
    return '设置条件'
  }
  const field = value.args?.[0]?.nameSnapshot
    || value.field?.nameSnapshot
    || value.fields?.[0]?.nameSnapshot
    || value.earlier?.nameSnapshot
  const label = predicateOps[value.op] || value.op || '条件'
  return field ? `${field} ${label}` : label
}

function paramSummary(value) {
  if (!value) {
    return '设置参数'
  }
  const text = JSON.stringify(value)
  return text.length > 28 ? `${text.slice(0, 28)}…` : text
}

function isStructuredValue(value) {
  return value != null && typeof value === 'object'
}

function listSummary(value) {
  if (!Array.isArray(value) || !value.length) {
    return '设置列表'
  }
  return value.map((item) => item?.nameSnapshot || item?.value || item).join(', ')
}

const predicateFieldOptions = computed(() => {
  if (!predicateDialog.row || !predicateDialog.column) {
    return []
  }
  return fieldOptionsFor(predicateDialog.row, predicateDialog.column)
})

function openPredicate(row, column) {
  const current = row.cells?.[column.key]
  Object.assign(predicateDialog, {
    visible: true,
    row,
    column,
    op: 'IS_NULL',
    fieldColumnId: '',
    secondColumnId: '',
    fieldColumnIds: [],
    value: '',
    secondValue: '',
    threshold: null,
    jsonMode: false,
    jsonText: current ? JSON.stringify(current, null, 2) : '{}',
  })
  if (current && typeof current === 'object') {
    predicateDialog.op = current.op || 'IS_NULL'
    const options = predicateFieldOptions.value
    const primary = current.args?.[0] || current.field || current.earlier
    predicateDialog.fieldColumnId = fieldRefModelId(primary, options)
    predicateDialog.secondColumnId = fieldRefModelId(current.later, options)
    if (Array.isArray(current.fields)) {
      predicateDialog.fieldColumnIds = current.fields
        .map((item) => fieldRefModelId(item, options))
        .filter(Boolean)
    }
    predicateDialog.threshold = current.threshold ?? null
    if (Array.isArray(current.values)) {
      predicateDialog.value = current.values.join(',')
    }
    const literal = current.args?.find((item) => item?.type === 'LITERAL')
    if (literal) {
      predicateDialog.value = literal.value ?? ''
    }
    const literals = current.args?.filter((item) => item?.type === 'LITERAL') ?? []
    if (literals.length > 1) {
      predicateDialog.secondValue = literals[1].value ?? ''
    }
  }
}

function selectedFieldRef(columnId) {
  return toFieldRef(predicateFieldOptions.value.find((option) => option.columnId === columnId))
}

function coerceValue(value) {
  if (value === '' || value == null) {
    return value
  }
  if (!Number.isNaN(Number(value)) && String(Number(value)) === String(value).trim()) {
    return Number(value)
  }
  return value
}

function literal(value) {
  return { type: 'LITERAL', value: coerceValue(value) }
}

function buildPredicate() {
  const op = predicateDialog.op
  const field = selectedFieldRef(predicateDialog.fieldColumnId)
  if (compositePredicateOps.has(op)) {
    return { op, predicates: [] }
  }
  if (['IS_NULL', 'IS_EMPTY', 'IS_BLANK'].includes(op)) {
    return { op, args: field ? [field] : [] }
  }
  if (['DUPLICATE', 'UNIQUE', 'DUPLICATE_RATE_GT'].includes(op)) {
    const fields = predicateDialog.fieldColumnIds
      .map(selectedFieldRef)
      .filter(Boolean)
    const result = { op, fields }
    if (predicateDialog.threshold != null) {
      result.threshold = Number(predicateDialog.threshold)
    }
    return result
  }
  if (op === 'DATE_ORDER_INVALID') {
    return {
      op,
      earlier: field,
      later: selectedFieldRef(predicateDialog.secondColumnId),
    }
  }
  if (['DATE_PARSEABLE', 'FK_EXISTS'].includes(op)) {
    return { op, field }
  }
  if (op === 'BETWEEN') {
    return { op, field, args: [literal(predicateDialog.value), literal(predicateDialog.secondValue)] }
  }
  if (op === 'IN' || op === 'NOT_IN') {
    return {
      op,
      field,
      values: String(predicateDialog.value || '')
        .split(',')
        .map((item) => item.trim())
        .filter(Boolean),
    }
  }
  if (op === 'ROW_COUNT_LT') {
    return { op, threshold: Number(predicateDialog.threshold) }
  }
  if (thresholdPredicateOps.has(op)) {
    const result = { op, threshold: Number(predicateDialog.threshold) }
    if (field && op !== 'ROW_COUNT_LT') {
      result.field = field
    }
    if (op === 'CATEGORY_RATIO_LT' && predicateDialog.value !== '') {
      result.value = literal(predicateDialog.value)
    }
    return result
  }
  if (['DATE_BEFORE', 'DATE_AFTER', 'REGEX_MATCH'].includes(op)) {
    return { op, field, args: [literal(predicateDialog.value)] }
  }
  return { op, args: field ? [field, literal(predicateDialog.value)] : [] }
}

function applyPredicate() {
  if (predicateDialog.jsonMode) {
    try {
      const parsed = JSON.parse(predicateDialog.jsonText)
      setCell(predicateDialog.row, predicateDialog.column, parsed)
      predicateDialog.visible = false
    } catch {
      ElMessage.error('条件 JSON 格式不正确')
    }
    return
  }
  if (compositePredicateOps.has(predicateDialog.op)) {
    predicateDialog.jsonMode = true
    ElMessage.warning('AND / OR / NOT 需要在 JSON 模式中配置 predicates，避免丢失组合条件')
    return
  }
  setCell(predicateDialog.row, predicateDialog.column, buildPredicate())
  predicateDialog.visible = false
}

function openParamEditor(row, column) {
  const current = row.cells?.[column.key]
  Object.assign(paramDialog, {
    visible: true,
    row,
    column,
    text: current ? JSON.stringify(current, null, 2) : '{}',
  })
}

function applyParamEditor() {
  try {
    const parsed = JSON.parse(paramDialog.text)
    setCell(paramDialog.row, paramDialog.column, parsed)
    paramDialog.visible = false
  } catch {
    ElMessage.error('参数 JSON 格式不正确')
  }
}

function addCustomColumn() {
  if (!activeSheet.value) {
    return
  }
  const title = customColumn.title.trim()
  if (!title) {
    ElMessage.warning('请填写列名称')
    return
  }
  const slug = title.toLowerCase().replace(/[^a-z0-9\u4e00-\u9fa5]+/g, '_').replace(/^_+|_+$/g, '') || 'field'
  let key = `custom_${slug}`
  let suffix = 2
  const keys = new Set(activeSheet.value.columns.map((column) => column.key))
  while (keys.has(key)) {
    key = `custom_${slug}_${suffix++}`
  }
  activeSheet.value.columns.push({
    key,
    title,
    cellType: customColumn.cellType,
    required: false,
    system: false,
    hidden: false,
    width: 180,
  })
  customColumn.title = ''
  customColumn.cellType = 'TEXT'
}

async function removeColumn(column) {
  if (column.system || !activeSheet.value) {
    return
  }
  try {
    await ElMessageBox.confirm(`确定删除自定义列「${column.title}」吗？`, '删除列', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  activeSheet.value.columns = activeSheet.value.columns.filter((item) => item.key !== column.key)
  for (const row of activeSheet.value.rows ?? []) {
    delete row.cells?.[column.key]
  }
}

function statusType(status) {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'VALIDATED') return 'primary'
  if (status === 'DEPRECATED') return 'info'
  return 'warning'
}

function runStatusType(status) {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'RUNNING') return 'primary'
  if (status === 'FAILED') return 'danger'
  if (status === 'CANCELLED') return 'info'
  return 'warning'
}

function nodeStatusType(status) {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'RUNNING') return 'primary'
  if (status === 'FAILED') return 'danger'
  if (status === 'SKIPPED') return 'info'
  return 'warning'
}

function formatJson(value) {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

function shortHash(value) {
  return value ? String(value).slice(0, 12) : '-'
}

function formatTime(value) {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function activateSheet(key) {
  activeSheetKey.value = key
}

onMounted(() => {
  loadDatasets()
  loadWorkbooks()
})

onBeforeUnmount(stopRunPolling)
</script>

<template>
  <div class="page-content">
    <el-breadcrumb separator="/" class="page-breadcrumb">
      <el-breadcrumb-item>工作台</el-breadcrumb-item>
      <el-breadcrumb-item>规则库</el-breadcrumb-item>
    </el-breadcrumb>

    <header class="page-header rule-page-header">
      <div>
        <h1>规则库</h1>
        <p>像工作表一样编排清洗、关联、聚合和质量门禁，发布后按不可变版本运行。</p>
      </div>
      <el-tag v-if="dirty" type="warning" effect="plain">有未保存修改</el-tag>
    </header>

    <el-card class="rule-create-card" shadow="never">
      <div class="rule-create-row">
        <el-select
          v-model="selectedDatasetId"
          class="create-dataset-select"
          placeholder="选择数据集"
          :loading="loadingDatasets"
          no-data-text="暂无 READY 数据集，请先在数据集库标记就绪"
          filterable
        >
          <el-option
            v-for="dataset in selectableDatasets"
            :key="dataset.id"
            :label="dataset.name"
            :value="dataset.id"
          >
            <span>{{ dataset.name }}</span>
            <span class="option-meta">{{ dataset.status }} · {{ dataset.tableCount }} 表 · {{ dataset.rowCount }} 行</span>
          </el-option>
        </el-select>
        <el-input
          v-model="newWorkbookName"
          class="create-name-input"
          placeholder="例如：订单训练数据清洗"
          maxlength="255"
          @keyup.enter="createWorkbook"
        />
        <el-button type="primary" :icon="DocumentAdd" :loading="creating" @click="createWorkbook">
          新建规则库
        </el-button>
        <div class="rule-toolbar-spacer"></div>
        <el-input
          v-model="workbookKeyword"
          class="workbook-search"
          placeholder="搜索规则库"
          clearable
          @keyup.enter="loadWorkbooks"
          @clear="loadWorkbooks"
        />
        <el-button :icon="Refresh" :loading="loadingWorkbooks" @click="loadWorkbooks">刷新</el-button>
      </div>
    </el-card>

    <el-card class="workbook-list-card" shadow="never">
      <template #header>
        <div class="card-heading">
          <strong>我的规则工作簿</strong>
          <span>{{ workbooks.length }} 个</span>
        </div>
      </template>
      <div v-loading="loadingWorkbooks">
        <el-empty v-if="!workbooks.length" :image-size="86" description="还没有规则库，先选择一个数据集并新建" />
        <div v-else class="workbook-list">
          <article
            v-for="workbook in workbooks"
            :key="workbook.id"
            class="workbook-item"
            :class="{ 'is-active': workbook.id === activeWorkbookId }"
          >
            <button type="button" class="workbook-open" @click="toggleWorkbook(workbook)">
              <div class="workbook-copy">
                <strong>{{ workbook.name }}</strong>
                <span>
                  {{ datasets.find((item) => item.id === workbook.datasetVersionId)?.name || `数据集 ${shortHash(workbook.datasetVersionId)}` }}
                  · 修订 {{ workbook.revision }}
                  · 更新于 {{ formatTime(workbook.updatedAt) }}
                </span>
              </div>
              <div class="workbook-meta">
                <el-tag size="small" :type="statusType(workbook.status)" effect="plain">
                  {{ workbook.status }}
                </el-tag>
                <el-icon><component :is="workbook.id === activeWorkbookId ? Setting : DocumentAdd" /></el-icon>
              </div>
            </button>
            <el-button
              link
              type="danger"
              :icon="Delete"
              class="workbook-delete"
              @click="removeWorkbook(workbook)"
            >
              删除
            </el-button>
          </article>
        </div>
      </div>
    </el-card>

    <el-card v-if="activeWorkbook" v-loading="loadingEditor" class="workbook-editor-card" shadow="never">
      <template #header>
        <div class="editor-header">
          <div>
            <div class="editor-title-line">
              <h2>{{ activeWorkbook.name }}</h2>
              <el-tag size="small" :type="statusType(activeWorkbook.status)" effect="plain">
                {{ activeWorkbook.status }}
              </el-tag>
              <el-tag v-if="dirty" size="small" type="warning" effect="plain">未保存</el-tag>
            </div>
            <div class="editor-meta">
              <span>数据集：{{ datasetName }}</span>
              <span>修订号：{{ activeWorkbook.revision }}</span>
              <span>最新发布：{{ latestVersion ? `v${latestVersion.versionNumber}` : '尚未发布' }}</span>
              <span v-if="latestRun">最近运行：{{ latestRun.status }} · {{ formatTime(latestRun.createdAt) }}</span>
            </div>
          </div>
          <div class="editor-actions">
            <el-button :icon="Refresh" :loading="saving" @click="persistDraft()">保存草稿</el-button>
            <el-button :loading="validating" @click="validateCurrent">校验</el-button>
            <el-button type="primary" :icon="Upload" :loading="publishing" @click="publishCurrent">
              发布
            </el-button>
            <el-button :icon="View" @click="openRunHistory">
              运行记录{{ runs.length ? ` (${runs.length})` : '' }}
            </el-button>
            <el-button :icon="VideoPlay" :loading="running" @click="runPreview">预览运行</el-button>
          </div>
        </div>
      </template>

      <el-alert
        v-if="validationResult"
        class="validation-alert"
        :type="validationResult.valid ? 'success' : 'error'"
        :title="validationResult.valid ? `校验通过，共 ${validationResult.nodeCount} 个节点` : '校验未通过'"
        :closable="false"
        show-icon
      >
        <ul v-if="validationResult.issues?.length" class="validation-list">
          <li v-for="issue in validationResult.issues" :key="`${issue.code}-${issue.sheetKey}-${issue.rowId}-${issue.columnKey}`">
            <strong>{{ issue.code }}</strong>
            <span>{{ issue.message }}</span>
            <em v-if="issue.fixSuggestion">{{ issue.fixSuggestion }}</em>
          </li>
        </ul>
      </el-alert>

      <div class="sheet-toolbar">
        <el-tabs v-model="activeSheetKey" class="sheet-tabs">
          <el-tab-pane
            v-for="sheet in activeWorkbook.document.sheets"
            :key="sheet.key"
            :label="`${sheet.title} (${sheet.rows?.length || 0})`"
            :name="sheet.key"
          />
        </el-tabs>
        <div class="sheet-actions">
          <el-button :icon="Plus" size="small" @click="addRow">添加规则行</el-button>
          <el-button :icon="Setting" size="small" @click="columnDialogVisible = true">列设置</el-button>
        </div>
      </div>

      <div v-if="activeSheet" class="grid-scroll">
        <table class="workbook-grid">
          <thead>
            <tr>
              <th class="row-index">#</th>
              <th class="row-enabled">启用</th>
              <th
                v-for="column in visibleColumns"
                :key="column.key"
                :style="{ minWidth: `${column.width || 180}px` }"
              >
                <span>{{ column.title }}</span>
                <sup v-if="column.required" class="required-mark">*</sup>
                <small v-if="!column.system" class="custom-mark">自定义</small>
              </th>
              <th class="row-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in activeSheet.rows || []" :key="row.id">
              <td class="row-index">{{ index + 1 }}</td>
              <td class="row-enabled">
                <el-switch v-model="row.enabled" size="small" />
              </td>
              <td v-for="column in visibleColumns" :key="column.key" class="rule-cell">
                <el-select
                  v-if="column.cellType === 'INPUT_REF'"
                  :model-value="inputRefModel(row.cells?.[column.key])"
                  size="small"
                  filterable
                  clearable
                  placeholder="选择表或上游输出"
                  @update:model-value="setInputRef(row, column, $event)"
                >
                  <el-option-group label="数据表">
                    <el-option
                      v-for="table in tableOptions"
                      :key="`table:${table.id}`"
                      :label="table.name"
                      :value="`TABLE:${table.id}`"
                    />
                  </el-option-group>
                  <el-option-group v-if="nodeOutputs.length" label="上游输出">
                    <el-option
                      v-for="output in nodeOutputs"
                      :key="`node:${output.nodeId}`"
                      :label="output.label"
                      :value="`NODE:${output.outputAlias}`"
                    />
                  </el-option-group>
                </el-select>

                <el-select
                  v-else-if="column.cellType === 'TABLE_REF'"
                  :model-value="tableRefModel(row, column)"
                  size="small"
                  filterable
                  clearable
                  placeholder="选择表"
                  @update:model-value="setTableRef(row, column, $event)"
                >
                  <el-option
                    v-for="table in tableOptions"
                    :key="table.id"
                    :label="table.name"
                    :value="table.id"
                  />
                </el-select>

                <el-select
                  v-else-if="column.cellType === 'FIELD_REF'"
                  :model-value="fieldRefModelIdFor(row, column)"
                  size="small"
                  filterable
                  clearable
                  :placeholder="parentTableId(row, column) ? '选择字段' : '先选择表'"
                  @update:model-value="setFieldRef(row, column, $event)"
                >
                  <el-option
                    v-for="option in fieldOptionsFor(row, column)"
                    :key="option.columnId"
                    :label="fieldOptionLabel(option)"
                    :value="option.columnId"
                  >
                    <span>{{ fieldOptionLabel(option) }}</span>
                    <span class="option-meta">{{ option.dataType }}</span>
                  </el-option>
                </el-select>

                <el-select
                  v-else-if="column.cellType === 'FIELD_LIST_REF'"
                  :model-value="fieldListModel(row, column)"
                  size="small"
                  multiple
                  collapse-tags
                  collapse-tags-tooltip
                  filterable
                  clearable
                  placeholder="选择字段组合"
                  @update:model-value="setFieldListRef(row, column, $event)"
                >
                  <el-option
                    v-for="option in fieldOptionsFor(row, column)"
                    :key="option.columnId"
                    :label="fieldOptionLabel(option)"
                    :value="option.columnId"
                  />
                </el-select>

                <el-select
                  v-else-if="column.cellType === 'NODE_OUTPUT_REF'"
                  :model-value="nodeOutputModel(row.cells?.[column.key])"
                  size="small"
                  filterable
                  clearable
                  placeholder="选择上游输出"
                  @update:model-value="setNodeOutputRef(row, column, $event)"
                >
                  <el-option
                    v-for="output in nodeOutputs"
                    :key="output.nodeId"
                    :label="output.label"
                    :value="output.outputAlias"
                  />
                </el-select>

                <el-button
                  v-else-if="column.cellType === 'PREDICATE'"
                  class="cell-value-button"
                  size="small"
                  :type="row.cells?.[column.key] ? 'primary' : 'default'"
                  plain
                  @click="openPredicate(row, column)"
                >
                  <span class="cell-button-text">{{ predicateSummary(row.cells?.[column.key]) }}</span>
                </el-button>

                <el-button
                  v-else-if="column.cellType === 'PARAM_OBJECT'"
                  class="cell-value-button"
                  size="small"
                  :type="row.cells?.[column.key] ? 'primary' : 'default'"
                  plain
                  @click="openParamEditor(row, column)"
                >
                  <span class="cell-button-text">{{ paramSummary(row.cells?.[column.key]) }}</span>
                </el-button>

                <el-button
                  v-else-if="column.cellType === 'VALUE_LIST'"
                  class="cell-value-button"
                  size="small"
                  plain
                  @click="openParamEditor(row, column)"
                >
                  <span class="cell-button-text">{{ listSummary(row.cells?.[column.key]) }}</span>
                </el-button>

                <el-select
                  v-else-if="column.cellType === 'NODE_KIND'
                    || column.cellType === 'EFFECT'
                    || column.cellType === 'OPERATION'
                    || column.cellType === 'JOIN_TYPE'
                    || column.cellType === 'CARDINALITY'
                    || column.cellType === 'ENUM'"
                  :model-value="row.cells?.[column.key] ?? ''"
                  size="small"
                  filterable
                  @update:model-value="setCell(row, column, $event)"
                >
                  <el-option
                    v-for="option in enumOptions(column)"
                    :key="option"
                    :label="option"
                    :value="option"
                  />
                </el-select>

                <el-switch
                  v-else-if="column.cellType === 'BOOLEAN'"
                  :model-value="row.cells?.[column.key] !== false"
                  size="small"
                  @update:model-value="setCell(row, column, $event)"
                />

                <el-input-number
                  v-else-if="column.cellType === 'NUMBER' || column.cellType === 'ORDER'"
                  :model-value="typeof row.cells?.[column.key] === 'number' ? row.cells[column.key] : null"
                  :controls="false"
                  size="small"
                  class="cell-number"
                  placeholder="数字"
                  @update:model-value="setCell(row, column, $event)"
                />

                <el-button
                  v-else-if="isStructuredValue(row.cells?.[column.key])"
                  class="cell-value-button"
                  size="small"
                  plain
                  @click="openParamEditor(row, column)"
                >
                  <span class="cell-button-text">{{ paramSummary(row.cells?.[column.key]) }}</span>
                </el-button>

                <el-input
                  v-else
                  :model-value="row.cells?.[column.key] ?? ''"
                  size="small"
                  :placeholder="column.required ? '必填' : '可选'"
                  @update:model-value="setCell(row, column, $event)"
                />
              </td>
              <td class="row-actions">
                <el-button link :icon="CopyDocument" title="复制" @click="duplicateRow(row)" />
                <el-button link type="danger" :icon="Delete" title="删除" @click="removeRow(row)" />
              </td>
            </tr>
            <tr v-if="!(activeSheet.rows || []).length">
              <td :colspan="visibleColumns.length + 3" class="grid-empty">
                当前工作表还没有规则行，点击“添加规则行”开始编排。
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </el-card>

    <el-dialog v-model="columnDialogVisible" title="列设置" width="720px" top="6vh">
      <div v-if="activeSheet" class="column-settings">
        <div class="column-setting-head">
          <span>列名称</span>
          <span>类型</span>
          <span>显示</span>
          <span></span>
        </div>
        <div v-for="column in activeSheet.columns" :key="column.key" class="column-setting-row">
          <el-input v-model="column.title" size="small" />
          <span class="column-type">{{ column.cellType }}</span>
          <el-switch v-model="column.hidden" size="small" active-text="隐藏" />
          <el-button
            link
            type="danger"
            :disabled="column.system"
            :icon="Delete"
            @click="removeColumn(column)"
          />
        </div>
      </div>
      <el-divider content-position="left">新增自定义列</el-divider>
      <div class="custom-column-form">
        <el-input v-model="customColumn.title" placeholder="例如：负责人、业务说明" />
        <el-select v-model="customColumn.cellType">
          <el-option v-for="type in customColumnTypes" :key="type" :label="type" :value="type" />
        </el-select>
        <el-button type="primary" :icon="Plus" @click="addCustomColumn">新增列</el-button>
      </div>
      <template #footer>
        <el-button type="primary" @click="columnDialogVisible = false">完成</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="predicateDialog.visible" title="配置条件" width="680px">
      <div class="dialog-mode-switch">
        <el-radio-group v-model="predicateDialog.jsonMode" size="small">
          <el-radio-button :value="false">可视化</el-radio-button>
          <el-radio-button :value="true">JSON</el-radio-button>
        </el-radio-group>
      </div>
      <template v-if="!predicateDialog.jsonMode">
        <el-form label-width="92px" label-position="left">
          <el-form-item label="条件类型">
            <el-select v-model="predicateDialog.op" class="full-width">
              <el-option
                v-for="(label, op) in predicateOps"
                :key="op"
                :label="`${label} (${op})`"
                :value="op"
              />
            </el-select>
          </el-form-item>
          <el-alert
            v-if="compositePredicateOps.has(predicateDialog.op)"
            class="composite-hint"
            type="info"
            :closable="false"
            show-icon
            title="组合条件请切换到 JSON 模式"
            description="AND / OR 使用 predicates 数组，NOT 使用 predicate 对象；保存时会自动切换，避免可视化编辑丢失嵌套结构。"
          />
          <el-form-item
            v-if="!fieldListPredicateOps.has(predicateDialog.op)
              && !noFieldPredicateOps.has(predicateDialog.op)
              && !compositePredicateOps.has(predicateDialog.op)"
            label="字段"
          >
            <el-select v-model="predicateDialog.fieldColumnId" class="full-width" filterable>
              <el-option
                v-for="option in predicateFieldOptions"
                :key="option.columnId"
                :label="fieldOptionLabel(option)"
                :value="option.columnId"
              />
            </el-select>
          </el-form-item>
          <el-form-item v-if="fieldListPredicateOps.has(predicateDialog.op)" label="字段组合">
            <el-select v-model="predicateDialog.fieldColumnIds" class="full-width" multiple filterable>
              <el-option
                v-for="option in predicateFieldOptions"
                :key="option.columnId"
                :label="fieldOptionLabel(option)"
                :value="option.columnId"
              />
            </el-select>
          </el-form-item>
          <el-form-item v-if="predicateDialog.op === 'DATE_ORDER_INVALID'" label="后一个字段">
            <el-select v-model="predicateDialog.secondColumnId" class="full-width" filterable>
              <el-option
                v-for="option in predicateFieldOptions"
                :key="option.columnId"
                :label="fieldOptionLabel(option)"
                :value="option.columnId"
              />
            </el-select>
          </el-form-item>
          <el-form-item
            v-if="valuePredicateOps.has(predicateDialog.op)"
            :label="predicateDialog.op === 'CATEGORY_RATIO_LT' ? '类目值' : '比较值'"
          >
            <el-input
              v-model="predicateDialog.value"
              :placeholder="['IN', 'NOT_IN'].includes(predicateDialog.op) ? '多个值用逗号分隔' : '输入比较值'"
            />
          </el-form-item>
          <el-form-item v-if="predicateDialog.op === 'BETWEEN'" label="区间">
            <div class="range-inputs">
              <el-input v-model="predicateDialog.value" placeholder="下界" />
              <el-input v-model="predicateDialog.secondValue" placeholder="上界" />
            </div>
          </el-form-item>
          <el-form-item v-if="thresholdPredicateOps.has(predicateDialog.op)" label="阈值">
            <el-input-number v-model="predicateDialog.threshold" :min="0" :max="1" :step="0.01" />
          </el-form-item>
        </el-form>
      </template>
      <el-input
        v-else
        v-model="predicateDialog.jsonText"
        class="json-editor"
        type="textarea"
        :rows="14"
        spellcheck="false"
      />
      <template #footer>
        <el-button @click="predicateDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="applyPredicate">应用条件</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="paramDialog.visible" title="配置节点参数" width="640px">
      <el-input
        v-model="paramDialog.text"
        class="json-editor"
        type="textarea"
        :rows="14"
        spellcheck="false"
      />
      <template #footer>
        <el-button @click="paramDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="applyParamEditor">应用参数</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="runHistoryVisible" title="运行记录" width="920px">
      <div v-loading="refreshingRuns" class="run-history-panel">
        <el-empty v-if="!runs.length" :image-size="72" description="还没有运行记录" />
        <el-table v-else :data="runs" size="small" stripe>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="runStatusType(row.status)" effect="plain">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="mode" label="模式" width="90" />
          <el-table-column prop="sampleSize" label="采样" width="80" />
          <el-table-column label="创建时间" min-width="150">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="开始时间" min-width="150">
            <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
          </el-table-column>
          <el-table-column label="结束时间" min-width="150">
            <template #default="{ row }">{{ formatTime(row.finishedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="viewRunFromHistory(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>

    <el-dialog
      v-model="runDetailVisible"
      title="运行详情"
      width="980px"
      @closed="resetRunDetail"
    >
      <div v-loading="loadingRun" class="run-detail-panel">
        <template v-if="selectedRun">
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="状态">
              <el-tag size="small" :type="runStatusType(selectedRun.status)" effect="plain">
                {{ selectedRun.status }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="模式">{{ selectedRun.mode }}</el-descriptions-item>
            <el-descriptions-item label="采样行数">{{ selectedRun.sampleSize || '全量' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ formatTime(selectedRun.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="开始时间">{{ formatTime(selectedRun.startedAt) }}</el-descriptions-item>
            <el-descriptions-item label="结束时间">{{ formatTime(selectedRun.finishedAt) }}</el-descriptions-item>
            <el-descriptions-item label="工作簿版本 ID" :span="2">{{ selectedRun.workbookVersionId }}</el-descriptions-item>
            <el-descriptions-item label="数据集版本 ID">{{ selectedRun.datasetVersionId }}</el-descriptions-item>
          </el-descriptions>

          <el-alert
            v-if="selectedRun.errorMessage"
            class="run-error-alert"
            type="error"
            :title="selectedRun.errorMessage"
            :closable="false"
            show-icon
          />

          <div class="run-plan-meta">
            <span>冻结计划哈希：<code>{{ shortHash(runExecution?.workbookVersionContentHash) }}</code></span>
            <span>计划节点：{{ runExecution?.compiledPlan?.nodeCount ?? runNodeRows.length }}</span>
            <span>依赖边：{{ runExecution?.compiledPlan?.edges?.length ?? 0 }}</span>
            <span v-if="runIsLive" class="run-live-hint">每 3 秒自动刷新</span>
          </div>

          <el-table :data="runNodeRows" size="small" stripe class="run-node-table">
            <el-table-column prop="nodeId" label="节点" min-width="210" show-overflow-tooltip />
            <el-table-column prop="nodeKind" label="类型" width="100" />
            <el-table-column prop="stage" label="阶段" width="100" />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="nodeStatusType(row.status)" effect="plain">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="指标" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">{{ formatJson(row.metrics) }}</template>
            </el-table-column>
            <el-table-column label="样例行" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.sampleUri || '-' }}</template>
            </el-table-column>
            <el-table-column label="错误" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.errorMessage || '-' }}</template>
            </el-table-column>
          </el-table>
        </template>
      </div>
      <template #footer>
        <span v-if="runIsLive" class="run-footer-hint">Worker 尚未结束本次运行</span>
        <el-button @click="closeRunDetail">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-content {
  width: min(1440px, 100%);
}

.rule-page-header {
  align-items: flex-end;
}

.rule-create-card {
  margin-bottom: 16px;
  border-radius: 15px;
}

.rule-create-card :deep(.el-card__body) {
  padding: 16px 18px;
}

.rule-create-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.create-dataset-select {
  width: 280px;
}

.create-name-input {
  width: 280px;
}

.rule-toolbar-spacer {
  flex: 1;
}

.workbook-search {
  width: 220px;
}

.option-meta {
  float: right;
  margin-left: 18px;
  color: var(--ink-faint);
  font-size: 11px;
}

.workbook-list-card {
  margin-bottom: 16px;
  border-radius: 15px;
}

.workbook-list-card :deep(.el-card__header) {
  padding: 0 18px 0 20px;
  border-bottom-color: var(--line);
}

.workbook-list-card :deep(.el-card__body) {
  padding: 14px 18px;
}

.card-heading {
  display: flex;
  min-height: 54px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.card-heading strong {
  font-size: 14px;
}

.card-heading span {
  color: var(--ink-faint);
  font-size: 12px;
}

.workbook-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.workbook-item {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 4px;
  padding: 6px 8px 6px 0;
  background: rgba(255, 255, 255, 0.55);
  border: 1px solid var(--line);
  border-radius: 12px;
  transition: border-color 0.16s ease, background 0.16s ease, box-shadow 0.16s ease;
}

.workbook-item:hover {
  background: #fff;
  border-color: var(--line-strong);
}

.workbook-item.is-active {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
  box-shadow: inset 3px 0 0 var(--el-color-primary);
}

.workbook-open {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px 8px 14px;
  color: inherit;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 9px;
}

.workbook-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.workbook-copy strong {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workbook-copy span {
  overflow: hidden;
  color: var(--ink-faint);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workbook-meta {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
  color: var(--ink-faint);
}

.workbook-delete {
  flex: 0 0 auto;
  padding-inline: 6px;
}

.workbook-editor-card {
  overflow: hidden;
  border-radius: 16px;
}

.workbook-editor-card :deep(.el-card__header) {
  padding: 16px 20px;
  border-bottom-color: var(--line);
}

.workbook-editor-card :deep(.el-card__body) {
  padding: 0 0 16px;
}

.editor-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}

.editor-title-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.editor-title-line h2 {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -0.02em;
}

.editor-meta {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  margin-top: 7px;
  color: var(--ink-faint);
  font-size: 11px;
}

.editor-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.validation-alert {
  margin: 14px 20px 0;
}

.validation-list {
  display: grid;
  gap: 7px;
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.validation-list li {
  display: flex;
  gap: 8px;
  align-items: baseline;
  font-size: 12px;
}

.validation-list em {
  color: var(--ink-faint);
  font-style: normal;
}

.sheet-toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  padding: 0 20px;
  border-bottom: 1px solid var(--line);
}

.sheet-tabs {
  min-width: 0;
  flex: 1;
}

.sheet-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.sheet-tabs :deep(.el-tabs__nav-wrap::after) {
  display: none;
}

.sheet-actions {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
  padding-bottom: 10px;
}

.grid-scroll {
  max-height: min(66vh, 760px);
  margin: 14px 20px 0;
  overflow: auto;
  background: #fff;
  border: 1px solid var(--line-strong);
  border-radius: 12px;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.7);
}

.workbook-grid {
  width: max-content;
  min-width: 100%;
  border-spacing: 0;
  border-collapse: separate;
  font-size: 12px;
}

.workbook-grid th,
.workbook-grid td {
  padding: 7px 8px;
  vertical-align: middle;
  border-right: 1px solid #edf0ee;
  border-bottom: 1px solid #edf0ee;
}

.workbook-grid thead th {
  position: sticky;
  top: 0;
  z-index: 5;
  height: 38px;
  color: #40564e;
  font-size: 11px;
  font-weight: 650;
  text-align: left;
  white-space: nowrap;
  background: #f4f8f6;
  box-shadow: inset 0 -1px 0 var(--line-strong);
}

.workbook-grid tbody tr:hover td {
  background: #fbfdfc;
}

.workbook-grid tbody tr:last-child td {
  border-bottom: 0;
}

.workbook-grid td:last-child,
.workbook-grid th:last-child {
  border-right: 0;
}

.row-index {
  position: sticky;
  left: 0;
  z-index: 3;
  width: 42px;
  min-width: 42px;
  color: var(--ink-faint);
  text-align: center !important;
  background: #f7faf8;
}

.workbook-grid thead .row-index {
  z-index: 7;
  background: #edf4f1;
}

.row-enabled {
  position: sticky;
  left: 42px;
  z-index: 3;
  width: 62px;
  min-width: 62px;
  text-align: center !important;
  background: #f7faf8;
  box-shadow: 2px 0 0 #edf0ee;
}

.workbook-grid thead .row-enabled {
  z-index: 7;
  background: #edf4f1;
}

.row-actions {
  width: 74px;
  min-width: 74px;
  text-align: center;
  white-space: nowrap;
}

.rule-cell {
  min-width: 150px;
  background: #fff;
}

.rule-cell :deep(.el-select),
.rule-cell :deep(.el-input),
.rule-cell :deep(.el-input-number) {
  width: 100%;
}

.rule-cell :deep(.el-input__wrapper) {
  padding-inline: 8px;
  background: transparent;
  box-shadow: none;
}

.rule-cell :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--line-strong) inset;
}

.rule-cell :deep(.el-input__wrapper.is-focus) {
  background: #fff;
  box-shadow: 0 0 0 1px var(--el-color-primary-light-5) inset;
}

.rule-cell :deep(.el-input-number .el-input__wrapper) {
  text-align: left;
}

.rule-cell :deep(.el-select__wrapper) {
  min-height: 30px;
  padding: 2px 8px;
  background: transparent;
  box-shadow: none;
}

.rule-cell :deep(.el-select__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--line-strong) inset;
}

.rule-cell :deep(.el-select__wrapper.is-focused) {
  background: #fff;
  box-shadow: 0 0 0 1px var(--el-color-primary-light-5) inset;
}

.cell-value-button {
  width: 100%;
  justify-content: flex-start;
  padding-inline: 8px;
  overflow: hidden;
  font-size: 11px;
}

.cell-button-text {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-number {
  width: 100%;
}

.grid-empty {
  height: 110px;
  color: var(--ink-faint);
  text-align: center !important;
  background: #fff;
}

.required-mark {
  margin-left: 2px;
  color: #c44536;
}

.custom-mark {
  margin-left: 6px;
  padding: 1px 5px;
  color: #6e7e77;
  font-size: 9px;
  background: #e9f0ed;
  border-radius: 999px;
}

.column-settings {
  max-height: 300px;
  overflow-y: auto;
}

.column-setting-head,
.column-setting-row {
  display: grid;
  grid-template-columns: minmax(160px, 1fr) 130px 90px 40px;
  gap: 10px;
  align-items: center;
  padding: 6px 4px;
}

.column-setting-head {
  color: var(--ink-faint);
  font-size: 11px;
  border-bottom: 1px solid var(--line);
}

.column-setting-row + .column-setting-row {
  border-top: 1px solid #f2f5f3;
}

.column-type {
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: 11px;
}

.custom-column-form {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) 150px auto;
  gap: 10px;
  align-items: center;
}

.dialog-mode-switch {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 14px;
}

.full-width {
  width: 100%;
}

.range-inputs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  width: 100%;
}

.json-editor :deep(textarea) {
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.55;
}

.composite-hint {
  margin-bottom: 14px;
}

.run-history-panel {
  min-height: 80px;
}

.run-detail-panel {
  min-height: 120px;
}

.run-error-alert {
  margin-top: 14px;
}

.run-plan-meta {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-top: 14px;
  color: var(--ink-faint);
  font-size: 11px;
}

.run-plan-meta code {
  padding: 2px 6px;
  color: var(--ink-soft);
  font-family: var(--font-mono);
  background: #f2f6f4;
  border-radius: 5px;
}

.run-live-hint {
  color: var(--el-color-primary);
}

.run-node-table {
  margin-top: 12px;
}

.run-footer-hint {
  float: left;
  padding-top: 7px;
  color: var(--ink-faint);
  font-size: 11px;
}

@media (max-width: 1100px) {
  .workbook-list {
    grid-template-columns: 1fr;
  }

  .editor-header {
    flex-direction: column;
  }

  .editor-actions {
    justify-content: flex-start;
  }
}

@media (max-width: 760px) {
  .create-dataset-select,
  .create-name-input,
  .workbook-search {
    width: 100%;
  }

  .sheet-toolbar {
    align-items: stretch;
    flex-direction: column;
    gap: 8px;
  }

  .sheet-actions {
    padding-bottom: 8px;
  }

  .grid-scroll {
    margin-inline: 10px;
  }

  .custom-column-form,
  .column-setting-head,
  .column-setting-row {
    grid-template-columns: 1fr;
  }
}
</style>
