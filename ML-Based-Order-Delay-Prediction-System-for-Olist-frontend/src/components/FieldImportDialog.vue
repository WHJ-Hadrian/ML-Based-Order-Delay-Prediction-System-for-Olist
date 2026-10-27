<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { DocumentCopy, Download, Search } from '@element-plus/icons-vue'
import { AuthenticationRequiredError } from '@/auth/keycloak'
import { listDatasets } from '@/api/datasets'
import {
  createFieldLibrary,
  getFieldCatalog,
  importFieldsToLibrary,
  listFieldLibraries,
} from '@/api/ruleLibrary'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  datasetId: { type: String, default: '' },
  defaultLibraryName: { type: String, default: '' },
  libraryId: { type: String, default: '' },
  lockLibrary: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue', 'imported'])

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

const libraries = ref([])
const datasets = ref([])
const catalog = ref([])
const loadingLibraries = ref(false)
const loadingDatasets = ref(false)
const loadingCatalog = ref(false)
const submitting = ref(false)
const targetLibraryId = ref('')
const newLibraryName = ref('')
const selectedDatasetId = ref('')
const selectedColumnIds = ref([])
const keyword = ref('')

const NEW_LIBRARY = '__new__'
const needsNewLibrary = computed(() => targetLibraryId.value === NEW_LIBRARY)

const selectedDataset = computed(
  () => datasets.value.find((item) => item.id === selectedDatasetId.value) || null,
)

const filteredCatalog = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  if (!query) {
    return catalog.value
  }
  return catalog.value.filter((row) =>
    [row.tableName, row.columnName, row.dataType]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(query)),
  )
})

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      reset()
      loadLibraries()
      loadDatasets()
    }
  },
)

function reset() {
  targetLibraryId.value = props.lockLibrary
    ? props.libraryId
    : props.libraryId || NEW_LIBRARY
  newLibraryName.value = props.defaultLibraryName || ''
  selectedDatasetId.value = props.datasetId || ''
  selectedColumnIds.value = []
  catalog.value = []
  keyword.value = ''
}

async function loadLibraries() {
  loadingLibraries.value = true
  try {
    libraries.value = (await listFieldLibraries()) || []
  } catch (error) {
    if (error instanceof AuthenticationRequiredError) {
      return
    }
    ElMessage.error(error?.message || '加载字段库失败')
  } finally {
    loadingLibraries.value = false
  }
}

async function loadDatasets() {
  loadingDatasets.value = true
  try {
    const result = await listDatasets({ page: 0, size: 200, hasData: true })
    datasets.value = (result?.items ?? []).filter((item) => item.tableCount > 0)
    if (!selectedDatasetId.value && datasets.value.length) {
      selectedDatasetId.value = datasets.value[0].id
    }
    if (!newLibraryName.value && selectedDataset.value) {
      newLibraryName.value = selectedDataset.value.name
    }
    if (selectedDatasetId.value) {
      await loadCatalog()
    }
  } catch (error) {
    if (error instanceof AuthenticationRequiredError) {
      return
    }
    ElMessage.error(error?.message || '加载数据集失败')
  } finally {
    loadingDatasets.value = false
  }
}

async function loadCatalog() {
  if (!selectedDatasetId.value) {
    catalog.value = []
    return
  }
  loadingCatalog.value = true
  selectedColumnIds.value = []
  try {
    catalog.value = (await getFieldCatalog(selectedDatasetId.value)) || []
  } catch (error) {
    catalog.value = []
    if (error instanceof AuthenticationRequiredError) {
      return
    }
    ElMessage.error(error?.message || '加载字段目录失败')
  } finally {
    loadingCatalog.value = false
  }
}

function handleDatasetChange() {
  keyword.value = ''
  if (!props.defaultLibraryName && selectedDataset.value) {
    newLibraryName.value = selectedDataset.value.name
  }
  loadCatalog()
}

function handleSelectionChange(rows) {
  selectedColumnIds.value = rows.map((row) => row.columnId)
}

function copySelected() {
  const rows = selectedColumnIds.value.length
    ? catalog.value.filter((row) => selectedColumnIds.value.includes(row.columnId))
    : filteredCatalog.value
  if (!rows.length) {
    ElMessage.warning('没有可复制的字段')
    return
  }
  const text = rows
    .map((row) =>
      [
        `${row.tableName || ''}.${row.columnName || ''}`.replace(/^\./, ''),
        `${row.tableName || ''}.${row.columnName || ''}`.replace(/^\./, ''),
        row.dataType || 'STRING',
        row.columnName || '',
      ].join('\t'),
    )
    .join('\n')
  if (!navigator.clipboard) {
    ElMessage.error('当前浏览器不支持剪贴板写入')
    return
  }
  navigator.clipboard
    .writeText(text)
    .then(() => ElMessage.success(`已复制 ${rows.length} 个字段到剪贴板`))
    .catch(() => ElMessage.error('复制失败，请检查浏览器剪贴板权限'))
}

async function submit() {
  if (submitting.value) {
    return
  }
  if (!selectedDatasetId.value) {
    ElMessage.warning('请选择数据集')
    return
  }
  if (needsNewLibrary.value && !newLibraryName.value.trim()) {
    ElMessage.warning('请输入新字段库的名称')
    return
  }
  if (!needsNewLibrary.value && !targetLibraryId.value) {
    ElMessage.warning('请选择目标字段库')
    return
  }
  submitting.value = true
  try {
    let libraryId = targetLibraryId.value
    if (needsNewLibrary.value) {
      const created = await createFieldLibrary({ name: newLibraryName.value.trim() })
      libraryId = created.id
    }
    const payload = { datasetVersionId: selectedDatasetId.value }
    if (selectedColumnIds.value.length) {
      payload.columnIds = selectedColumnIds.value
    }
    const result = await importFieldsToLibrary(libraryId, payload)
    ElMessage.success(`导入完成：新增 ${result.created} 个，跳过 ${result.skipped} 个`)
    emit('imported', { libraryId, result })
    visible.value = false
  } catch (error) {
    if (error instanceof AuthenticationRequiredError) {
      return
    }
    ElMessage.error(error?.message || '导入字段失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="从数据集导入字段" width="780px" append-to-body>
    <div class="import-form">
      <div v-if="!lockLibrary" class="form-row">
        <label>目标字段库</label>
        <el-select
          v-model="targetLibraryId"
          class="grow"
          placeholder="选择字段库"
          :loading="loadingLibraries"
        >
          <el-option
            v-for="library in libraries"
            :key="library.id"
            :label="`${library.name}（${library.fieldCount} 个字段）`"
            :value="library.id"
          />
          <el-option label="＋ 新建字段库" :value="NEW_LIBRARY" />
        </el-select>
      </div>

      <div v-if="needsNewLibrary" class="form-row">
        <label>新字段库名称</label>
        <el-input
          v-model="newLibraryName"
          class="grow"
          maxlength="128"
          placeholder="默认使用数据集名称"
        />
      </div>

      <div class="form-row">
        <label>数据集</label>
        <el-select
          v-model="selectedDatasetId"
          class="grow"
          placeholder="选择数据集"
          filterable
          :loading="loadingDatasets"
          @change="handleDatasetChange"
        >
          <el-option
            v-for="dataset in datasets"
            :key="dataset.id"
            :label="dataset.name"
            :value="dataset.id"
          >
            <span>{{ dataset.name }}</span>
            <span class="option-meta">{{ dataset.tableCount }} 表 · {{ dataset.rowCount }} 行</span>
          </el-option>
        </el-select>
      </div>

      <div class="catalog-toolbar">
        <el-input
          v-model="keyword"
          class="catalog-search"
          placeholder="搜索表、字段或类型"
          clearable
          :prefix-icon="Search"
        />
        <span class="catalog-count">
          已选 {{ selectedColumnIds.length }} / 共 {{ filteredCatalog.length }} 个字段
        </span>
        <el-button :icon="DocumentCopy" @click="copySelected">复制选中</el-button>
      </div>

      <el-table
        v-loading="loadingCatalog"
        :data="filteredCatalog"
        row-key="columnId"
        height="360"
        border
        empty-text="该数据集暂无字段"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="46" />
        <el-table-column prop="tableName" label="数据表" min-width="160" show-overflow-tooltip />
        <el-table-column prop="columnName" label="物理字段" min-width="170" show-overflow-tooltip />
        <el-table-column prop="dataType" label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.dataType || 'STRING' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <el-tag v-if="row.imported" size="small" type="info">已导入</el-tag>
            <span v-else class="muted">可导入</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :icon="Download" :loading="submitting" @click="submit">
        导入字段
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.import-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.form-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.form-row label {
  width: 96px;
  flex: none;
  color: var(--ink-soft);
  font-size: 13px;
  text-align: right;
}

.grow {
  flex: 1;
}

.catalog-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 2px;
}

.catalog-search {
  width: 240px;
}

.catalog-count {
  flex: 1;
  color: var(--ink-faint);
  font-size: 12px;
}

.option-meta {
  float: right;
  margin-left: 20px;
  color: #8a9b93;
  font-size: 11px;
}

.muted {
  color: var(--ink-faint);
  font-size: 12px;
}
</style>
