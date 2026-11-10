<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft,
  ArrowRight,
  Check,
  Close,
  Collection,
  Delete,
  DocumentCopy,
  EditPen,
  FolderAdd,
  Grid,
  Plus,
  Refresh,
  Search,
} from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { AuthenticationRequiredError, useAuth } from '@/auth/keycloak'
import FieldImportDialog from '@/components/FieldImportDialog.vue'
import {
  bulkCreateFieldLibraryFields,
  createFieldLibrary,
  deleteFieldLibrary,
  deleteFieldLibraryField,
  importFieldsToLibrary,
  listFieldLibraries,
  listFieldLibraryFields,
  updateFieldLibrary,
  updateFieldLibraryField,
} from '@/api/ruleLibrary'

const auth = useAuth()
const route = useRoute()
const router = useRouter()

const TABLE_PAGE_SIZE = 5
const FIELD_PREVIEW_LIMIT = 8
const MANUAL_GROUP_KEY = '__manual__'
const MANUAL_GROUP_NAME = '手动添加'
const MANUAL_TABLE_NAME = '未指定数据表'
const DATA_TYPES = ['STRING', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'DATETIME']
const FIELD_STATUSES = ['ACTIVE', 'ARCHIVED']

const libraries = ref([])
const libraryKeyword = ref('')
const fieldSearch = reactive({})
const loadingLibraries = ref(false)
const expandedLibraryIds = ref([])
const expandedDatasetKeys = ref([])
const expandedTableKeys = ref([])
const fieldsByLibrary = reactive({})
const loadingFields = reactive({})
const draftsByLibrary = reactive({})
const tablePages = reactive({})
const expandedFieldTables = reactive({})
const focusedLibraryId = ref('')
const libraryPage = ref(0)
const libraryPageSize = ref(5)
const extractDatasetId = ref('')
const extractDatasetName = ref('')
const extractReturn = ref(null)
const extractResult = ref(null)
const importingIntoLibrary = ref(false)


const creatingLibrary = ref(false)
const editingLibraryId = ref('')
const libraryNameDraft = ref('')
const libraryNameInputs = ref({})
let cancelLibraryNameOnBlur = false

const importVisible = ref(false)
const importLibraryId = ref('')

const editVisible = ref(false)
const editingFieldLibraryId = ref('')
const editingRow = ref(null)
const savingField = ref(false)
const fieldForm = reactive({
  name: '',
  displayName: '',
  dataType: 'STRING',
  semanticRole: '',
  sourceTable: '',
  description: '',
  status: 'ACTIVE',
})

const savingDrafts = ref(false)

const pasteVisible = ref(false)
const pasteText = ref('')
const pasteLibraryId = ref('')
const pasteContext = reactive({
  datasetId: '',
  datasetName: MANUAL_GROUP_NAME,
  tableName: MANUAL_TABLE_NAME,
})
const draftNameInputs = ref({})

const pasteTargetLabel = computed(
  () => `${pasteContext.datasetName || MANUAL_GROUP_NAME} / ${pasteContext.tableName || MANUAL_TABLE_NAME}`,
)

const totalFields = computed(() =>
  libraries.value.reduce((sum, library) => sum + (library.fieldCount || 0), 0),
)

const pagedLibraries = computed(() => {
  const start = libraryPage.value * libraryPageSize.value
  return libraries.value.slice(start, start + libraryPageSize.value)
})

const showLibraryPagination = computed(() => libraries.value.length > 0)

watch(
  () => route.query,
  (query) => {
    if (query.extract) {
      extractDatasetId.value = String(query.extract)
      extractDatasetName.value = String(query.name || '')
      extractReturn.value = {
        dataset: String(query.extract),
        keyword: String(query.keyword || ''),
        format: String(query.format || ''),
        page: Number.parseInt(String(query.page || '0'), 10) || 0,
        size: Number.parseInt(String(query.size || '10'), 10) || 10,
        tab: String(query.tab || 'all'),
      }
      extractResult.value = null
      libraryPage.value = 0
      focusedLibraryId.value = ''
      return
    }
    extractDatasetId.value = ''
    extractDatasetName.value = ''
  },
  { immediate: true },
)

watch(
  () => auth.state.status,
  (status) => {
    if (status === 'authenticated') {
      loadLibraries()
    } else if (status === 'anonymous') {
      libraries.value = []
      expandedLibraryIds.value = []
      for (const key of Object.keys(fieldsByLibrary)) {
        delete fieldsByLibrary[key]
      }
    }
  },
  { immediate: true },
)

async function loadLibraries() {
  loadingLibraries.value = true
  try {
    libraries.value = (await listFieldLibraries({ keyword: libraryKeyword.value })) || []
    const maxPage = Math.max(
      0,
      Math.ceil(libraries.value.length / libraryPageSize.value) - 1,
    )
    if (libraryPage.value > maxPage) {
      libraryPage.value = maxPage
    }
  } catch (error) {
    if (error instanceof AuthenticationRequiredError) {
      return
    }
    ElMessage.error(error?.message || '加载字段库失败')
  } finally {
    loadingLibraries.value = false
  }
}

async function loadFields(libraryId) {
  if (!libraryId || loadingFields[libraryId]) {
    return
  }
  loadingFields[libraryId] = true
  try {
    fieldsByLibrary[libraryId] = (await listFieldLibraryFields(libraryId)) || []
  } catch (error) {
    fieldsByLibrary[libraryId] = []
    if (error instanceof AuthenticationRequiredError) {
      return
    }
    ElMessage.error(error?.message || '加载字段失败')
  } finally {
    loadingFields[libraryId] = false
  }
}
async function toggleLibrary(library) {
  const id = library.id
  if (expandedLibraryIds.value.includes(id)) {
    expandedLibraryIds.value = expandedLibraryIds.value.filter((value) => value !== id)
    return
  }
  expandedLibraryIds.value = [...expandedLibraryIds.value, id]
  if (!fieldSearch[id]) {
    fieldSearch[id] = { input: '', applied: '' }
  }
  await loadFields(id)
  const keys = groupsFor(id).map((group) => datasetKey(id, group.key))
  expandedDatasetKeys.value = [...new Set([...expandedDatasetKeys.value, ...keys])]
}

function applyFieldSearch(libraryId) {
  if (!fieldSearch[libraryId]) {
    fieldSearch[libraryId] = { input: '', applied: '' }
  }
  fieldSearch[libraryId].applied = fieldSearch[libraryId].input.trim()
  for (const group of groupsFor(libraryId)) {
    delete tablePages[tableKey(libraryId, group.key)]
  }
}

function resetFieldSearch(libraryId) {
  if (!fieldSearch[libraryId]) {
    fieldSearch[libraryId] = { input: '', applied: '' }
  }
  fieldSearch[libraryId].input = ''
  fieldSearch[libraryId].applied = ''
  for (const group of groupsFor(libraryId)) {
    delete tablePages[tableKey(libraryId, group.key)]
  }
}

function searchLibraries() {
  libraryPage.value = 0
  loadLibraries()
}

function resetLibrarySearch() {
  libraryKeyword.value = ''
  libraryPage.value = 0
  loadLibraries()
}
function isLibraryExpanded(libraryId) {
  return expandedLibraryIds.value.includes(libraryId)
}

function datasetKey(libraryId, groupKey) {
  return `${libraryId}:${groupKey}`
}

function tableKey(libraryId, groupKey, tableName) {
  return `${libraryId}:${groupKey}:${tableName}`
}

function isDatasetExpanded(libraryId, groupKey) {
  return expandedDatasetKeys.value.includes(datasetKey(libraryId, groupKey))
}

function toggleDataset(libraryId, groupKey) {
  const key = datasetKey(libraryId, groupKey)
  if (expandedDatasetKeys.value.includes(key)) {
    expandedDatasetKeys.value = expandedDatasetKeys.value.filter((value) => value !== key)
    return
  }
  expandedDatasetKeys.value = [...expandedDatasetKeys.value, key]
}

function tablePageFor(libraryId, groupKey, total) {
  const raw = tablePages[tableKey(libraryId, groupKey)] || 0
  if (typeof total !== 'number') {
    return raw
  }
  const maxPage = Math.max(0, Math.ceil(total / TABLE_PAGE_SIZE) - 1)
  return Math.min(raw, maxPage)
}

function pagedTables(libraryId, group) {
  const page = tablePageFor(libraryId, group.key, group.tables.length)
  const start = page * TABLE_PAGE_SIZE
  return group.tables.slice(start, start + TABLE_PAGE_SIZE)
}

function handleTablePageChange(libraryId, groupKey, page) {
  tablePages[tableKey(libraryId, groupKey)] = page - 1
}

function isFieldTableExpanded(libraryId, groupKey, tableName) {
  return Boolean(expandedFieldTables[tableKey(libraryId, groupKey, tableName)])
}

function toggleFieldTable(libraryId, groupKey, tableName) {
  const key = tableKey(libraryId, groupKey, tableName)
  expandedFieldTables[key] = !expandedFieldTables[key]
}

function visibleFields(libraryId, groupKey, table) {
  if (isFieldTableExpanded(libraryId, groupKey, table.name)) {
    return table.fields
  }
  return table.fields.slice(0, FIELD_PREVIEW_LIMIT)
}
function isTableExpanded(libraryId, groupKey, tableName) {
  return expandedTableKeys.value.includes(tableKey(libraryId, groupKey, tableName))
}

function toggleTable(libraryId, groupKey, tableName) {
  const key = tableKey(libraryId, groupKey, tableName)
  if (expandedTableKeys.value.includes(key)) {
    expandedTableKeys.value = expandedTableKeys.value.filter((value) => value !== key)
    return
  }
  expandedTableKeys.value = [...expandedTableKeys.value, key]
}

function groupsFor(libraryId) {
  const list = fieldsByLibrary[libraryId] || []
  const query = (fieldSearch[libraryId]?.applied || '').trim().toLowerCase()
  const filtered = query
    ? list.filter((field) =>
        [
          field.name,
          field.displayName,
          field.sourceDatasetName,
          field.sourceTableName,
          field.sourceColumnName,
          field.dataType,
        ]
          .filter(Boolean)
          .some((value) => String(value).toLowerCase().includes(query)),
      )
    : list

  const map = new Map()
  const ensureGroup = (key, name) => {
    if (!map.has(key)) {
      map.set(key, { key, name, tables: new Map(), count: 0, draftCount: 0 })
    }
    return map.get(key)
  }
  const ensureTable = (group, tableName) => {
    if (!group.tables.has(tableName)) {
      group.tables.set(tableName, { name: tableName, fields: [], drafts: [] })
    }
    return group.tables.get(tableName)
  }

  for (const field of filtered) {
    const key = field.sourceDatasetId || MANUAL_GROUP_KEY
    const group = ensureGroup(key, field.sourceDatasetName || MANUAL_GROUP_NAME)
    group.count += 1
    ensureTable(group, field.sourceTableName || MANUAL_TABLE_NAME).fields.push(field)
  }

  for (const draft of draftsFor(libraryId)) {
    const key = draft.datasetId || MANUAL_GROUP_KEY
    const group = ensureGroup(key, draft.datasetName || MANUAL_GROUP_NAME)
    group.draftCount += 1
    ensureTable(group, draft.tableName || MANUAL_TABLE_NAME).drafts.push(draft)
  }

  const result = [...map.values()].map((group) => ({
    key: group.key,
    name: group.name,
    count: group.count,
    draftCount: group.draftCount,
    tables: [...group.tables.values()],
  }))
  result.sort((left, right) => {
    if (left.key === MANUAL_GROUP_KEY) {
      return 1
    }
    if (right.key === MANUAL_GROUP_KEY) {
      return -1
    }
    return left.name.localeCompare(right.name, 'zh-Hans-CN')
  })
  return result
}
async function createLibrary() {
  if (creatingLibrary.value) {
    return
  }
  creatingLibrary.value = true
  try {
    const created = await createFieldLibrary({})
    libraryKeyword.value = ''
    libraryPage.value = 0
    libraries.value = [created, ...libraries.value.filter((item) => item.id !== created.id)]
    expandedLibraryIds.value = [...new Set([...expandedLibraryIds.value, created.id])]
    if (!fieldSearch[created.id]) {
      fieldSearch[created.id] = { input: '', applied: '' }
    }
    await loadFields(created.id)
    await startRenameLibrary(created)
    ElMessage.success(`已创建「${created.name}」，可以直接修改名称`)
  } catch (error) {
    if (error instanceof AuthenticationRequiredError) {
      return
    }
    ElMessage.error(error?.message || '创建字段库失败')
  } finally {
    creatingLibrary.value = false
  }
}

function handleWorkbenchCommand(event) {
  if (event.detail === 'fields:create') createLibrary()
}

onMounted(() => window.addEventListener('workbench:command', handleWorkbenchCommand))
onBeforeUnmount(() => window.removeEventListener('workbench:command', handleWorkbenchCommand))

function handleLibraryPageChange(page) {
  libraryPage.value = page - 1
  focusedLibraryId.value = ''
}

function changeLibrarySize(size) {
  libraryPageSize.value = size
  libraryPage.value = 0
  focusedLibraryId.value = ''
}

function handleLibraryClick(library) {
  if (extractDatasetId.value) {
    confirmImportIntoLibrary(library)
    return
  }
  toggleLibrary(library)
}

function exitExtractMode({ goBack = false } = {}) {
  const fallbackDatasetId = extractDatasetId.value
  const hadExtractQuery = Boolean(route.query.extract)
  extractDatasetId.value = ''
  extractDatasetName.value = ''
  if (goBack) {
    returnToDatasetLibrary(fallbackDatasetId)
    return
  }
  if (hadExtractQuery) {
    router.replace({ path: '/fields' })
  }
}

function hasUnsavedDrafts() {
  return Object.values(draftsByLibrary).some((rows) => Array.isArray(rows) && rows.length > 0)
}

async function returnToDatasetLibrary(fallbackDatasetId = '') {
  if (hasUnsavedDrafts()) {
    try {
      await ElMessageBox.confirm(
        '字段库里还有尚未保存的字段草稿，返回数据集库后这些草稿会丢失。',
        '还有未保存的字段',
        {
          confirmButtonText: '仍然返回',
          cancelButtonText: '留在这里',
          type: 'warning',
        },
      )
    } catch {
      return
    }
  }
  const origin = extractReturn.value || {}
  const datasetId = origin.dataset || fallbackDatasetId
  const query = {}
  if (datasetId) query.dataset = datasetId
  if (origin.keyword) query.keyword = origin.keyword
  if (origin.format) query.format = origin.format
  if (origin.page) query.page = String(origin.page)
  if (origin.size && origin.size !== 10) query.size = String(origin.size)
  if (origin.tab && origin.tab !== 'all') query.tab = origin.tab
  router.push({ path: '/datasets', query })
}

async function confirmImportIntoLibrary(library) {
  if (importingIntoLibrary.value) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `把数据集「${extractDatasetName.value || '当前数据集'}」的字段导入到字段库「${library.name}」？`,
      '提取字段',
      { type: 'info', confirmButtonText: '导入', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  importingIntoLibrary.value = true
  try {
    const result = await importFieldsToLibrary(library.id, {
      datasetVersionId: extractDatasetId.value,
    })
    ElMessage.success(`导入完成：新增 ${result.created} 个，跳过 ${result.skipped} 个`)
    if (!expandedLibraryIds.value.includes(library.id)) {
      expandedLibraryIds.value = [...expandedLibraryIds.value, library.id]
    }
    if (!fieldSearch[library.id]) {
      fieldSearch[library.id] = { input: '', applied: '' }
    }
    await loadFields(library.id)
    library.fieldCount = (library.fieldCount || 0) + result.created
    library.datasetCount = Math.max(library.datasetCount || 0, 1)
    const datasetName = extractDatasetName.value || '当前数据集'
    exitExtractMode()
    extractResult.value = {
      count: result.created,
      skipped: result.skipped,
      libraryName: library.name,
      datasetName,
    }
  } catch (error) {
    if (error instanceof AuthenticationRequiredError) {
      return
    }
    ElMessage.error(error?.message || '导入字段失败')
  } finally {
    importingIntoLibrary.value = false
  }
}

async function createLibraryAndImport() {
  if (creatingLibrary.value || !extractDatasetId.value) {
    return
  }
  creatingLibrary.value = true
  try {
    const created = await createFieldLibrary({})
    libraryKeyword.value = ''
    libraryPage.value = 0
    libraries.value = [created, ...libraries.value.filter((item) => item.id !== created.id)]
    expandedLibraryIds.value = [...new Set([...expandedLibraryIds.value, created.id])]
    if (!fieldSearch[created.id]) {
      fieldSearch[created.id] = { input: '', applied: '' }
    }
    startRenameLibrary(created)
    importingIntoLibrary.value = true
    try {
      const result = await importFieldsToLibrary(created.id, {
        datasetVersionId: extractDatasetId.value,
      })
      created.fieldCount = result.created
      created.datasetCount = 1
      await loadFields(created.id)
      ElMessage.success(`已导入 ${result.created} 个字段，可以直接修改字段库名称`)
      const datasetName = extractDatasetName.value || '当前数据集'
      exitExtractMode()
      extractResult.value = {
        count: result.created,
        skipped: result.skipped,
        libraryName: created.name,
        datasetName,
      }
    } catch (error) {
      if (error instanceof AuthenticationRequiredError) {
        return
      }
      ElMessage.error(error?.message || '导入字段失败')
    } finally {
      importingIntoLibrary.value = false
    }
  } catch (error) {
    if (error instanceof AuthenticationRequiredError) {
      return
    }
    ElMessage.error(error?.message || '创建字段库失败')
  } finally {
    creatingLibrary.value = false
  }
}
async function startRenameLibrary(library) {
  editingLibraryId.value = library.id
  libraryNameDraft.value = library.name
  await nextTick()
  const input = libraryNameInputs.value[library.id]
  input?.focus()
  input?.select?.()
}

function setLibraryNameInput(el, id) {
  if (el) {
    libraryNameInputs.value[id] = el
  } else {
    delete libraryNameInputs.value[id]
  }
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
  const current = libraries.value.find((item) => item.id === library.id)
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
    const updated = await updateFieldLibrary(library.id, {
      name,
      description: current.description || '',
    })
    Object.assign(current, updated)
    ElMessage.success('名称已更新')
  } catch (error) {
    current.name = previousName
    if (error instanceof AuthenticationRequiredError) {
      return
    }
    ElMessage.error(error?.message || '重命名失败')
  }
}

async function removeLibrary(library) {
  try {
    await ElMessageBox.confirm(`删除字段库「${library.name}」及其中的全部字段？`, '删除字段库', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteFieldLibrary(library.id)
    ElMessage.success('字段库已删除')
    expandedLibraryIds.value = expandedLibraryIds.value.filter((value) => value !== library.id)
    delete fieldsByLibrary[library.id]
    delete draftsByLibrary[library.id]
    await loadLibraries()
  } catch (error) {
    if (error instanceof AuthenticationRequiredError) {
      return
    }
    ElMessage.error(error?.message || '删除字段库失败')
  }
}

const EMPTY_DRAFTS = []

function draftsFor(libraryId) {
  return draftsByLibrary[libraryId] || EMPTY_DRAFTS
}

function ensureDrafts(libraryId) {
  if (!draftsByLibrary[libraryId]) {
    draftsByLibrary[libraryId] = []
  }
  return draftsByLibrary[libraryId]
}

function draftBelongsTo(row, groupKey, tableName) {
  return (
    (row.datasetId || MANUAL_GROUP_KEY) === groupKey &&
    (row.tableName || MANUAL_TABLE_NAME) === tableName
  )
}

function groupContext(groupKey, groupName, tableName) {
  return {
    datasetId: groupKey === MANUAL_GROUP_KEY ? '' : groupKey,
    datasetName: groupName || MANUAL_GROUP_NAME,
    tableName: tableName || MANUAL_TABLE_NAME,
  }
}

function tableContext(group, tableName) {
  return groupContext(group.key, group.name, tableName)
}

function addDraftRow(libraryId, context = {}) {
  const rows = ensureDrafts(libraryId)
  const row = {
    __draft: true,
    key: `draft-${Date.now()}-${rows.length}-${Math.random().toString(36).slice(2, 6)}`,
    datasetId: context.datasetId || '',
    datasetName: context.datasetName || MANUAL_GROUP_NAME,
    tableName: context.tableName || MANUAL_TABLE_NAME,
    name: '',
    displayName: '',
    dataType: 'STRING',
    sourceColumnName: '',
    semanticRole: '',
    description: '',
  }
  rows.push(row)
  return row
}

function removeDraftRow(libraryId, key) {
  draftsByLibrary[libraryId] = draftsFor(libraryId).filter((row) => row.key !== key)
}

function clearTableDrafts(libraryId, groupKey, tableName) {
  draftsByLibrary[libraryId] = draftsFor(libraryId).filter(
    (row) => !draftBelongsTo(row, groupKey, tableName),
  )
}

function setDraftNameInput(el, key) {
  if (el) {
    draftNameInputs.value[key] = el
  } else {
    delete draftNameInputs.value[key]
  }
}

async function focusDraftInput(key) {
  await nextTick()
  draftNameInputs.value[key]?.focus?.()
}

function tableRows(libraryId, groupKey, table) {
  return [...visibleFields(libraryId, groupKey, table), ...(table.drafts || [])]
}

function draftRowClassName({ row }) {
  return row.__draft ? 'is-draft-row' : ''
}

async function expandTableContext(libraryId, groupKey, tableName) {
  if (!expandedLibraryIds.value.includes(libraryId)) {
    expandedLibraryIds.value = [...expandedLibraryIds.value, libraryId]
  }
  const dataset = datasetKey(libraryId, groupKey)
  if (!expandedDatasetKeys.value.includes(dataset)) {
    expandedDatasetKeys.value = [...expandedDatasetKeys.value, dataset]
  }
  const table = tableKey(libraryId, groupKey, tableName)
  if (!expandedTableKeys.value.includes(table)) {
    expandedTableKeys.value = [...expandedTableKeys.value, table]
  }
}

async function addFieldToTable(libraryId, group, tableName) {
  await expandTableContext(libraryId, group.key, tableName)
  const row = addDraftRow(libraryId, tableContext(group, tableName))
  await focusDraftInput(row.key)
}

async function addManualField(libraryId) {
  await expandTableContext(libraryId, MANUAL_GROUP_KEY, MANUAL_TABLE_NAME)
  const row = addDraftRow(libraryId, {
    datasetId: '',
    datasetName: MANUAL_GROUP_NAME,
    tableName: MANUAL_TABLE_NAME,
  })
  await focusDraftInput(row.key)
}
function draftPayload(row) {
  return {
    name: row.name.trim(),
    displayName: row.displayName.trim() || undefined,
    dataType: row.dataType,
    semanticRole: row.semanticRole.trim() || undefined,
    sourceTable:
      row.tableName && row.tableName !== MANUAL_TABLE_NAME ? row.tableName : undefined,
    sourceTableName:
      row.tableName && row.tableName !== MANUAL_TABLE_NAME ? row.tableName : undefined,
    sourceDatasetId: row.datasetId || undefined,
    sourceColumnName: row.sourceColumnName?.trim() || undefined,
    description: row.description.trim() || undefined,
    status: 'ACTIVE',
  }
}

async function persistDrafts(libraryId, rows, groupKey, tableName) {
  if (savingDrafts.value) {
    return
  }
  const savingRows = rows.filter((row) => row.name.trim())
  if (!savingRows.length) {
    ElMessage.warning('请至少填写一个字段名')
    return
  }
  savingDrafts.value = true
  try {
    const result = await bulkCreateFieldLibraryFields(
      libraryId,
      savingRows.map((row) => draftPayload(row)),
    )
    ElMessage.success(
      `保存完成：新增 ${result.created} 个${result.skipped ? `，跳过 ${result.skipped} 个` : ''}`,
    )
    if (result.errors?.length) {
      ElMessage.warning(result.errors.slice(0, 3).join('；'))
    }
    const savedKeys = new Set(savingRows.map((row) => row.key))
    draftsByLibrary[libraryId] = draftsFor(libraryId).filter(
      (row) => !savedKeys.has(row.key),
    )
    await refreshLibrary(libraryId)
    if (typeof groupKey === 'string') {
      await expandTableContext(libraryId, groupKey, tableName)
    }
  } catch (error) {
    if (error instanceof AuthenticationRequiredError) {
      return
    }
    ElMessage.error(error?.message || '保存字段失败')
  } finally {
    savingDrafts.value = false
  }
}

async function saveDrafts(libraryId, groupKey, tableName) {
  const scopedRows = draftsFor(libraryId).filter((row) =>
    draftBelongsTo(row, groupKey, tableName),
  )
  await persistDrafts(libraryId, scopedRows, groupKey, tableName)
}

async function saveDraftRow(libraryId, row) {
  if (!row.name.trim()) {
    ElMessage.warning('请先填写字段名')
    return
  }
  await persistDrafts(
    libraryId,
    [row],
    row.datasetId || MANUAL_GROUP_KEY,
    row.tableName || MANUAL_TABLE_NAME,
  )
}

function openEditor(libraryId, field) {
  editingFieldLibraryId.value = libraryId
  editingRow.value = field
  Object.assign(fieldForm, {
    name: field.name,
    displayName: field.displayName || '',
    dataType: field.dataType || 'STRING',
    semanticRole: field.semanticRole || '',
    sourceTable: field.sourceTable || '',
    description: field.description || '',
    status: field.status || 'ACTIVE',
  })
  editVisible.value = true
}

async function saveField() {
  if (!editingRow.value || savingField.value) {
    return
  }
  if (!fieldForm.name.trim()) {
    ElMessage.warning('字段名不能为空')
    return
  }
  savingField.value = true
  try {
    await updateFieldLibraryField(editingFieldLibraryId.value, editingRow.value.id, {
      name: fieldForm.name.trim(),
      displayName: fieldForm.displayName,
      dataType: fieldForm.dataType,
      semanticRole: fieldForm.semanticRole,
      sourceTable: fieldForm.sourceTable,
      description: fieldForm.description,
      status: fieldForm.status,
    })
    editVisible.value = false
    ElMessage.success('字段已保存')
    await refreshLibrary(editingFieldLibraryId.value)
  } catch (error) {
    if (error instanceof AuthenticationRequiredError) {
      return
    }
    ElMessage.error(error?.message || '保存字段失败')
  } finally {
    savingField.value = false
  }
}

async function removeField(libraryId, field) {
  try {
    await ElMessageBox.confirm(`删除字段「${field.name}」？`, '删除字段', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteFieldLibraryField(libraryId, field.id)
    ElMessage.success('字段已删除')
    await refreshLibrary(libraryId)
  } catch (error) {
    if (error instanceof AuthenticationRequiredError) {
      return
    }
    ElMessage.error(error?.message || '删除字段失败')
  }
}

async function refreshLibrary(libraryId) {
  await loadFields(libraryId)
  await loadLibraries()
}

function openImport(libraryId) {
  importLibraryId.value = libraryId
  importVisible.value = true
}

async function handleImported() {
  if (importLibraryId.value) {
    await refreshLibrary(importLibraryId.value)
  }
}
function openPaste(libraryId, context = {}) {
  pasteLibraryId.value = libraryId
  Object.assign(pasteContext, {
    datasetId: context.datasetId || '',
    datasetName: context.datasetName || MANUAL_GROUP_NAME,
    tableName: context.tableName || MANUAL_TABLE_NAME,
  })
  pasteText.value = ''
  pasteVisible.value = true
  if (!navigator.clipboard) {
    return
  }
  navigator.clipboard
    .readText()
    .then((text) => {
      if (text) {
        pasteText.value = text
      }
    })
    .catch(() => {})
}

function splitCells(line) {
  if (line.includes('\t')) {
    return line.split('\t').map((value) => value.trim())
  }
  if (line.includes(',')) {
    return line.split(',').map((value) => value.trim())
  }
  if (line.includes('|')) {
    return line.split('|').map((value) => value.trim())
  }
  if (line.includes(';')) {
    return line.split(';').map((value) => value.trim())
  }
  return [line.trim()]
}

async function applyPaste() {
  const lines = pasteText.value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
  if (!lines.length) {
    ElMessage.warning('剪贴板内容为空')
    return
  }
  const libraryId = pasteLibraryId.value
  const context = { ...pasteContext }
  const rows = ensureDrafts(libraryId)
  let added = 0
  let firstKey = ''
  for (const line of lines) {
    const cells = splitCells(line)
    const name = cells[0] || ''
    if (!name) {
      continue
    }
    const third = String(cells[2] || '').trim().toUpperCase()
    const thirdIsType = DATA_TYPES.includes(third)
    const key = `paste-${Date.now()}-${added}`
    rows.push({
      __draft: true,
      key,
      datasetId: context.datasetId,
      datasetName: context.datasetName,
      tableName: context.tableName,
      name,
      displayName: cells[1] || name,
      dataType: thirdIsType ? third : 'STRING',
      sourceColumnName: thirdIsType ? cells[3] || '' : cells[2] || '',
      semanticRole: '',
      description: cells[4] || '',
    })
    if (!firstKey) {
      firstKey = key
    }
    added += 1
  }
  pasteVisible.value = false
  if (added) {
    await expandTableContext(
      libraryId,
      context.datasetId || MANUAL_GROUP_KEY,
      context.tableName || MANUAL_TABLE_NAME,
    )
    await focusDraftInput(firstKey)
    ElMessage.success(
      `已解析 ${added} 行，目标：${context.datasetName} / ${context.tableName}。确认后点击保存`,
    )
  } else {
    ElMessage.warning('没有解析到有效字段')
  }
}
</script>
<template>
  <div class="page-content">
    <h1 class="visually-hidden">字段定义</h1>
    <div v-if="extractDatasetId" class="extract-banner">
      <div class="extract-banner-copy">
        <strong>正在为数据集「{{ extractDatasetName || '当前数据集' }}」提取字段</strong>
      </div>
      <div class="extract-banner-actions">
        <el-button
          type="primary"
          :icon="FolderAdd"
          :loading="creatingLibrary"
          @click="createLibraryAndImport"
        >
          新建字段库并导入
        </el-button>
        <el-button :icon="ArrowLeft" @click="exitExtractMode({ goBack: true })">返回数据集库</el-button>
      </div>
    </div>

    <div v-if="extractResult" class="extract-result" role="status" aria-live="polite">
      <span class="extract-result-icon"><el-icon><Check /></el-icon></span>
      <div class="extract-result-copy">
        <strong>已导入 {{ extractResult.count }} 个字段</strong>
        <span>
          数据集「{{ extractResult.datasetName }}」已导入到字段库「{{ extractResult.libraryName }}」<template v-if="extractResult.skipped">，跳过 {{ extractResult.skipped }} 个重复字段</template>。
        </span>
      </div>
      <div class="extract-result-actions">
        <el-button @click="extractResult = null">留在字段库</el-button>
        <el-button type="primary" :icon="ArrowLeft" @click="returnToDatasetLibrary()">返回数据集库</el-button>
      </div>
    </div>

    <el-card class="toolbar-card" shadow="never">
      <div class="library-toolbar">
        <el-input
          v-model="libraryKeyword"
          class="search-input"
          size="large"
          placeholder="搜索字段库名称"
          clearable
          :prefix-icon="Search"
          @keyup.enter="searchLibraries"
          @clear="searchLibraries"
        />
        <el-button size="large" :icon="Search" @click="searchLibraries">搜索</el-button>
        <el-button size="large" :icon="Refresh" @click="resetLibrarySearch">重置</el-button>
        <span class="toolbar-spacer"></span>
        <span class="toolbar-count">
          当前共 <strong>{{ libraries.length }}</strong> 个字段库 · <strong>{{ totalFields }}</strong> 个字段
        </span>
      </div>
    </el-card>

    <el-card class="list-card" shadow="never">
      <template #header>
        <div class="list-header">
          <span class="list-title">字段库目录</span>
        </div>
      </template>

      <div
        class="library-list"
        :class="{ 'is-picking': !!extractDatasetId, 'has-focus': !!focusedLibraryId }"
      >
        <p v-if="loadingLibraries" class="plain-loading">正在读取字段库…</p>
        <article
          v-for="library in pagedLibraries"
          :key="library.id"
          class="library-item"
          :class="{
            'is-expanded': isLibraryExpanded(library.id),
            'is-focused': focusedLibraryId === library.id,
          }"
          @mouseenter="focusedLibraryId = library.id"
          @mouseleave="focusedLibraryId = ''"
        >
          <div class="library-summary" @click="handleLibraryClick(library)">
            <span class="row-chevron" :class="{ 'is-open': isLibraryExpanded(library.id) }">
              <el-icon><ArrowRight /></el-icon>
            </span>

            <div class="library-name-block">
              <el-input
                v-if="editingLibraryId === library.id"
                :ref="(el) => setLibraryNameInput(el, library.id)"
                v-model="libraryNameDraft"
                class="name-input"
                size="large"
                maxlength="128"
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
                @click.stop="handleLibraryClick(library)"
              >
                {{ library.name }}
              </button>
              <span
                v-if="library.description"
                class="library-description"
                :title="library.description"
              >
                {{ library.description }}
              </span>
            </div>

            <div class="summary-metrics">
              <span class="metric-chip">
                <el-icon><Grid /></el-icon>
                {{ library.fieldCount }} 个字段
              </span>
              <span v-if="library.datasetCount" class="metric-chip">
                <el-icon><Collection /></el-icon>
                {{ library.datasetCount }} 个数据集
              </span>
              <span v-else class="metric-chip is-muted">手动字段库</span>
            </div>

            <div v-if="!extractDatasetId" class="row-actions" @click.stop>
              <el-button link type="primary" :icon="EditPen" @click="startRenameLibrary(library)">
                重命名
              </el-button>
              <el-button link type="danger" :icon="Delete" @click="removeLibrary(library)">
                删除
              </el-button>
            </div>
            <span v-else class="extract-row-hint">
              {{ focusedLibraryId === library.id ? '点击整行导入' : '' }}
            </span>
          </div>

          <transition name="accordion">
            <section v-if="isLibraryExpanded(library.id)" class="library-expanded" @click.stop>
              <div class="expanded-inner">
                <p v-if="loadingFields[library.id]" class="plain-loading">正在读取字段…</p>
                <div class="field-search-row">
                  <el-input
                    v-model="fieldSearch[library.id].input"
                    class="field-search-input"
                    size="small"
                    placeholder="搜索字段、数据表或数据集"
                    clearable
                    :prefix-icon="Search"
                    @keyup.enter="applyFieldSearch(library.id)"
                    @clear="resetFieldSearch(library.id)"
                  />
                  <el-button size="small" :icon="Search" @click="applyFieldSearch(library.id)">
                    搜索
                  </el-button>
                  <el-button size="small" @click="resetFieldSearch(library.id)">重置</el-button>
                  <span class="expanded-hint">
                    已筛选
                    {{ groupsFor(library.id).reduce((sum, group) => sum + group.count, 0) }}
                    / {{ (fieldsByLibrary[library.id] || []).length }} 个字段
                  </span>
                  <el-button
                    size="small"
                    type="primary"
                    plain
                    :icon="Refresh"
                    @click="openImport(library.id)"
                  >
                    从数据集导入
                  </el-button>
                </div>
                <div v-if="groupsFor(library.id).length" class="dataset-groups">
                  <div
                    v-for="group in groupsFor(library.id)"
                    :key="group.key"
                    class="dataset-group"
                  >
                    <div class="group-summary" @click="toggleDataset(library.id, group.key)">
                      <span
                        class="row-chevron small"
                        :class="{ 'is-open': isDatasetExpanded(library.id, group.key) }"
                      >
                        <el-icon><ArrowRight /></el-icon>
                      </span>
                      <el-icon class="group-icon"><Collection /></el-icon>
                      <strong class="group-name" :title="group.name">{{ group.name }}</strong>
                      <span class="group-meta">{{ group.count }} 个字段</span>
                      <span v-if="group.draftCount" class="draft-badge">
                        {{ group.draftCount }} 行待保存
                      </span>
                    </div>

                    <div v-if="isDatasetExpanded(library.id, group.key)" class="group-body">
                      <div
                        v-for="table in pagedTables(library.id, group)"
                        :key="table.name"
                        class="table-group"
                      >
                        <div
                          class="table-summary"
                          @click="toggleTable(library.id, group.key, table.name)"
                        >
                          <span
                            class="row-chevron small"
                            :class="{
                              'is-open': isTableExpanded(library.id, group.key, table.name),
                            }"
                          >
                            <el-icon><ArrowRight /></el-icon>
                          </span>
                          <el-icon class="table-icon"><Grid /></el-icon>
                          <strong class="table-name" :title="table.name">{{ table.name }}</strong>
                          <span class="table-meta">{{ table.fields.length }} 个字段</span>
                          <span v-if="table.drafts.length" class="draft-badge">
                            {{ table.drafts.length }} 行待保存
                          </span>
                          <div class="table-summary-actions" @click.stop>
                            <el-tooltip content="读剪贴板到本表" placement="top">
                              <el-button
                                link
                                class="table-paste-button"
                                :icon="DocumentCopy"
                                aria-label="读剪贴板"
                                @click="openPaste(library.id, tableContext(group, table.name))"
                              />
                            </el-tooltip>
                          </div>
                        </div>

                        <div
                          v-if="isTableExpanded(library.id, group.key, table.name)"
                          class="table-fields"
                        >
                          <el-table
                            :data="tableRows(library.id, group.key, table)"
                            :row-key="(row) => (row.__draft ? row.key : row.id)"
                            :row-class-name="draftRowClassName"
                            size="small"
                            border
                          >
                            <el-table-column
                              label="逻辑字段"
                              min-width="190"
                              resizable
                              show-overflow-tooltip
                            >
                              <template #default="{ row }">
                                <el-input
                                  v-if="row.__draft"
                                  :ref="(el) => setDraftNameInput(el, row.key)"
                                  v-model="row.name"
                                  size="small"
                                  placeholder="字段名，如 orders.id"
                                />
                                <span v-else class="field-name">
                                  {{ row.name }}
                                </span>
                              </template>
                            </el-table-column>
                            <el-table-column
                              label="显示名"
                              min-width="160"
                              resizable
                              show-overflow-tooltip
                            >
                              <template #default="{ row }">
                                <el-input
                                  v-if="row.__draft"
                                  v-model="row.displayName"
                                  size="small"
                                  placeholder="可选"
                                />
                                <span v-else-if="row.displayName">{{ row.displayName }}</span>
                                <span v-else class="field-empty">—</span>
                              </template>
                            </el-table-column>
                            <el-table-column label="类型" width="128" resizable>
                              <template #default="{ row }">
                                <el-select v-if="row.__draft" v-model="row.dataType" size="small">
                                  <el-option
                                    v-for="type in DATA_TYPES"
                                    :key="type"
                                    :label="type"
                                    :value="type"
                                  />
                                </el-select>
                                <el-tag v-else size="small" effect="plain">{{ row.dataType }}</el-tag>
                              </template>
                            </el-table-column>
                            <el-table-column
                              label="来源列"
                              min-width="150"
                              resizable
                              show-overflow-tooltip
                            >
                              <template #default="{ row }">
                                <el-input
                                  v-if="row.__draft"
                                  v-model="row.sourceColumnName"
                                  size="small"
                                  placeholder="可选"
                                />
                                <span v-else class="field-source">
                                  {{ row.sourceColumnName || '手动添加' }}
                                </span>
                              </template>
                            </el-table-column>
                            <el-table-column label="操作" width="132" :resizable="false">
                              <template #default="{ row }">
                                <template v-if="row.__draft">
                                  <el-button
                                    link
                                    type="primary"
                                    :icon="Check"
                                    :loading="savingDrafts"
                                    @click="saveDraftRow(library.id, row)"
                                  >
                                    保存
                                  </el-button>
                                  <el-button
                                    link
                                    type="danger"
                                    :icon="Close"
                                    @click="removeDraftRow(library.id, row.key)"
                                  />
                                </template>
                                <template v-else>
                                  <el-button
                                    link
                                    type="primary"
                                    :icon="EditPen"
                                    @click="openEditor(library.id, row)"
                                  >
                                    编辑
                                  </el-button>
                                  <el-button
                                    link
                                    type="danger"
                                    :icon="Delete"
                                    @click="removeField(library.id, row)"
                                  />
                                </template>
                              </template>
                            </el-table-column>
                          </el-table>
                          <div
                            v-if="table.drafts.length || table.fields.length > FIELD_PREVIEW_LIMIT"
                            class="table-footer"
                          >
                            <div class="table-footer-left">
                              <el-button
                                v-if="table.drafts.length"
                                link
                                type="primary"
                                :loading="savingDrafts"
                                @click="saveDrafts(library.id, group.key, table.name)"
                              >
                                保存全部（{{ table.drafts.length }}）
                              </el-button>
                              <el-button
                                v-if="table.drafts.length"
                                link
                                @click="clearTableDrafts(library.id, group.key, table.name)"
                              >
                                清空待保存
                              </el-button>
                            </div>
                            <el-button
                              v-if="table.fields.length > FIELD_PREVIEW_LIMIT"
                              link
                              type="primary"
                              @click="toggleFieldTable(library.id, group.key, table.name)"
                            >
                              {{
                                isFieldTableExpanded(library.id, group.key, table.name)
                                  ? '收起字段'
                                  : `展开全部 ${table.fields.length} 个字段`
                              }}
                            </el-button>
                          </div>
                          <button
                            type="button"
                            class="field-add-row"
                            @click="addFieldToTable(library.id, group, table.name)"
                          >
                            <el-icon><Plus /></el-icon>
                            <span>添加一行</span>
                          </button>
                        </div>
                      </div>
                      <div
                        v-if="group.tables.length > TABLE_PAGE_SIZE"
                        class="group-pagination"
                      >
                        <el-pagination
                          :current-page="tablePageFor(library.id, group.key, group.tables.length) + 1"
                          :page-size="TABLE_PAGE_SIZE"
                          :total="group.tables.length"
                          layout="prev, pager, next"
                          size="small"
                          background
                          @current-change="(page) => handleTablePageChange(library.id, group.key, page)"
                        />
                      </div>
                    </div>
                  </div>
                  <button
                    type="button"
                    class="field-add-row manual-add-row"
                    @click="addManualField(library.id)"
                  >
                    <el-icon><Plus /></el-icon>
                    <span>手动添加一个不属于具体数据集的字段</span>
                  </button>
                </div>

                <div v-else class="empty-state">
                  <p>暂无字段。</p>
                  <el-button type="primary" :icon="Refresh" @click="openImport(library.id)">
                    从数据集导入
                  </el-button>
                  <el-button :icon="Plus" @click="addManualField(library.id)">
                    手动添加字段
                  </el-button>
                </div>
              </div>
            </section>
          </transition>
        </article>

        <div v-if="!loadingLibraries && libraries.length === 0" class="empty-state"><p>暂无字段库。</p></div>

        <div v-if="showLibraryPagination" class="library-pagination">
          <span class="pagination-hint">每页 {{ libraryPageSize }} 个字段库</span>
          <el-pagination
            :current-page="libraryPage + 1"
            :page-size="libraryPageSize"
            :page-sizes="[5, 10, 20]"
            :total="libraries.length"
            layout="sizes, prev, pager, next, jumper"
            background
            @current-change="handleLibraryPageChange"
            @size-change="changeLibrarySize"
          />
        </div>
      </div>
    </el-card>

    <FieldImportDialog
      v-model="importVisible"
      :library-id="importLibraryId"
      lock-library
      @imported="handleImported"
    />


    <el-dialog v-model="pasteVisible" title="读剪贴板" width="620px">
      <p class="paste-target">
        写入位置：<strong>{{ pasteTargetLabel }}</strong>
      </p>
      <p class="paste-hint">
        每行一个字段，支持 Tab、逗号、竖线或分号分隔。顺序：字段名、显示名、类型、来源列、说明。
        解析后会直接出现在对应数据表的待保存行里。
      </p>
      <el-input
        v-model="pasteText"
        type="textarea"
        :rows="9"
        placeholder="orders.id    orders.id    STRING    order_id"
      />
      <template #footer>
        <el-button @click="pasteVisible = false">取消</el-button>
        <el-button type="primary" @click="applyPaste">解析为待保存行</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑字段定义" width="560px">
      <el-form label-width="88px" label-position="left">
        <el-form-item label="字段名" required>
          <el-input v-model="fieldForm.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="fieldForm.displayName" maxlength="255" />
        </el-form-item>
        <el-form-item label="数据类型">
          <el-select v-model="fieldForm.dataType" class="full-width">
            <el-option v-for="type in DATA_TYPES" :key="type" :label="type" :value="type" />
          </el-select>
        </el-form-item>
        <el-form-item label="语义角色">
          <el-input v-model="fieldForm.semanticRole" placeholder="例如 ORDER_ID、DELIVERY_DATE" />
        </el-form-item>
        <el-form-item label="来源表">
          <el-input v-model="fieldForm.sourceTable" maxlength="255" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="fieldForm.description" type="textarea" :rows="3" maxlength="1000" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="fieldForm.status">
            <el-radio v-for="status in FIELD_STATUSES" :key="status" :value="status">
              {{ status === 'ACTIVE' ? '启用' : '归档' }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingField" @click="saveField">保存</el-button>
      </template>
    </el-dialog>
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
  width: 260px;
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

.library-list.is-picking.has-focus .library-item {
  filter: grayscale(0.35);
  opacity: 0.38;
  transition: opacity 0.15s ease, filter 0.15s ease, box-shadow 0.15s ease;
}

.library-list.is-picking .library-item.is-focused {
  background: #fff;
  filter: none;
  opacity: 1;
  box-shadow: inset 3px 0 0 var(--accent);
}

.library-list.is-picking .library-summary {
  cursor: pointer;
}

.extract-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
  padding: 14px 18px;
  background: #f5f7f6;
  border: 1px solid var(--line);
  border-radius: var(--radius-panel);
  flex-wrap: wrap;
}

.extract-banner-copy {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.extract-banner-copy strong {
  color: var(--ink);
  font-size: 14px;
}

.extract-banner-copy span {
  color: var(--ink-soft);
  font-size: 12px;
}

.extract-banner-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.extract-row-hint {
  margin-left: auto;
  color: var(--accent);
  font-size: 12px;
  font-weight: 600;
}

.extract-result {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 16px;
  padding: 14px 18px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(53, 107, 88, 0.18);
  border-radius: var(--radius-panel);
  box-shadow: 0 10px 26px rgba(10, 45, 37, 0.06);
  flex-wrap: wrap;
}

.extract-result-icon {
  display: grid;
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  place-items: center;
  color: #0f5c49;
  font-size: 17px;
  background: #e2f3eb;
  border-radius: 50%;
}

.extract-result-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}

.extract-result-copy strong {
  color: var(--ink);
  font-size: 14px;
}

.extract-result-copy span {
  color: var(--ink-soft);
  font-size: 12px;
  line-height: 1.55;
}

.extract-result-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.library-pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 0 8px;
}

.pagination-hint {
  color: var(--ink-faint);
  font-size: 12px;
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
  background: rgba(53, 107, 88, 0.04);
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

.row-chevron.small {
  width: 16px;
  height: 16px;
  flex-basis: 16px;
  font-size: 11px;
}

.library-name-block {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
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
  width: 260px;
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
  background: rgba(53, 107, 88, 0.06);
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
  margin-left: auto;
  flex-wrap: wrap;
}

.library-expanded {
  padding: 0 20px 18px 44px;
  background: rgba(53, 107, 88, 0.02);
}

.expanded-inner {
  padding-top: 8px;
}


.expanded-hint {
  margin-left: auto;
  color: var(--ink-faint);
  font-size: 12px;
  white-space: nowrap;
}

.field-search-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.field-search-input {
  width: 280px;
  flex: 1;
  max-width: 460px;
}

.library-tip {
  margin: -4px 0 12px;
  color: var(--ink-faint);
  font-size: 11px;
  line-height: 1.5;
}

.draft-badge {
  display: inline-flex;
  align-items: center;
  padding: 1px 7px;
  border-radius: var(--radius-control);
  background: rgba(53, 107, 88, 0.1);
  color: var(--accent);
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}

.manual-add-row {
  height: auto;
  min-height: 42px;
  padding: 0 12px;
  margin-top: 0;
  line-height: 1.4;
}
.dataset-groups {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.dataset-group {
  overflow: visible;
  background: transparent;
  border: 0;
  border-top: 1px solid var(--line);
  border-radius: 0;
}

.group-summary,
.table-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.group-summary:hover,
.table-summary:hover {
  background: rgba(53, 107, 88, 0.04);
}

.group-icon {
  color: var(--accent);
}

.group-name {
  min-width: 0;
  overflow: hidden;
  color: var(--ink);
  font-size: 13px;
  font-weight: 620;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.group-meta,
.table-meta {
  color: var(--ink-faint);
  font-size: 11px;
}

.table-summary-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  margin-left: auto;
}

.table-summary-actions :deep(.el-button) {
  margin-left: 0;
}

.group-body {
  padding: 0 14px 12px 32px;
}

.table-group {
  margin-top: 8px;
  border: 0;
  border-top: 1px solid var(--line-subtle);
  border-radius: 0;
}

.table-icon {
  color: var(--ink-faint);
}

.table-name {
  min-width: 0;
  overflow: hidden;
  color: var(--ink-soft);
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-fields {
  padding: 0 8px 10px;
}

.field-add-row {
  display: flex;
  width: 100%;
  height: 42px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 0;
  margin-top: 8px;
  color: var(--el-color-primary);
  font-family: inherit;
  font-size: 12px;
  font-weight: 600;
  background: #fff;
  border: 1px dashed #bfd6ce;
  border-radius: var(--radius-control);
  cursor: pointer;
  transition: background 0.18s ease, border-color 0.18s ease;
}

.field-add-row:hover {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-5);
}

.table-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 6px 2px 2px;
  flex-wrap: wrap;
}

.table-footer-left {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}

.table-fields :deep(.is-draft-row > td.el-table__cell) {
  background: rgba(53, 107, 88, 0.055);
}

.table-fields :deep(.is-draft-row:hover > td.el-table__cell) {
  background: rgba(53, 107, 88, 0.08) !important;
}

.group-pagination {
  display: flex;
  justify-content: center;
  padding: 8px 0 2px;
}

.field-empty {
  color: var(--ink-faint);
}

.field-name {
  color: var(--ink);
  font-weight: 560;
}


.table-paste-button {
  color: var(--ink-soft);
}

.table-paste-button:hover {
  color: var(--el-color-primary);
}

.field-source {
  color: var(--ink-faint);
  font-size: 12px;
}

.paste-target {
  margin: 0 0 8px;
  color: var(--ink-soft);
  font-size: 12px;
}

.paste-target strong {
  color: var(--ink);
}

.paste-hint {
  margin: 0 0 10px;
  color: var(--ink-faint);
  font-size: 12px;
  line-height: 1.6;
}

.full-width {
  width: 100%;
}

.empty-state {
  padding: 24px 0 32px;
}

@media (max-width: 960px) {
  .search-input {
    width: 100%;
  }

  .library-summary {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .row-actions {
    width: 100%;
    margin-left: 28px;
  }

  .library-expanded {
    padding-left: 20px;
  }

}
</style>
