<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Download, EditPen, Refresh, Search } from '@element-plus/icons-vue'
import { AuthenticationRequiredError, useAuth } from '@/auth/keycloak'
import { listDatasets } from '@/api/datasets'
import {
  getField,
  getFieldCatalog,
  importFields,
  updateField,
} from '@/api/ruleLibrary'

const router = useRouter()
const auth = useAuth()
const datasets = ref([])
const catalog = ref([])
const loadingDatasets = ref(false)
const loadingCatalog = ref(false)
const importing = ref(false)
const selectedDatasetId = ref('')
const keyword = ref('')
const editVisible = ref(false)
const saving = ref(false)
const editingRow = ref(null)

const dataTypes = ['STRING', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'DATETIME']
const fieldStatuses = ['ACTIVE', 'ARCHIVED']
const form = reactive({
  name: '',
  displayName: '',
  dataType: 'STRING',
  semanticRole: '',
  sourceTable: '',
  description: '',
  status: 'ACTIVE',
})

const selectedDataset = computed(
  () => datasets.value.find((item) => item.id === selectedDatasetId.value) || null,
)

const filteredCatalog = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  if (!query) {
    return catalog.value
  }
  return catalog.value.filter((row) =>
    [row.tableName, row.columnName, row.fieldName, row.fieldDisplayName, row.dataType]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(query)),
  )
})

const importedCount = computed(() => catalog.value.filter((row) => row.imported).length)

watch(
  () => auth.state.status,
  (status) => {
    if (status === 'authenticated') {
      loadDatasets()
    } else if (status === 'anonymous') {
      datasets.value = []
      catalog.value = []
    }
  },
  { immediate: true },
)

async function loadDatasets() {
  loadingDatasets.value = true
  try {
    const result = await listDatasets({ page: 0, size: 100, hasData: true })
    datasets.value = (result?.items ?? []).filter((item) => item.tableCount > 0)
    if (!selectedDatasetId.value && datasets.value.length) {
      selectedDatasetId.value = datasets.value[0].id
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
  try {
    catalog.value = await getFieldCatalog(selectedDatasetId.value)
  } catch (error) {
    if (error instanceof AuthenticationRequiredError) {
      return
    }
    catalog.value = []
    ElMessage.error(error?.message || '加载字段目录失败')
  } finally {
    loadingCatalog.value = false
  }
}

async function handleDatasetChange() {
  keyword.value = ''
  await loadCatalog()
}

async function handleImport() {
  if (!selectedDatasetId.value || importing.value) {
    return
  }
  importing.value = true
  try {
    const result = await importFields(selectedDatasetId.value)
    await loadCatalog()
    ElMessage.success(`导入完成：新增 ${result.created} 个，跳过 ${result.skipped} 个`)
  } catch (error) {
    if (error instanceof AuthenticationRequiredError) {
      return
    }
    ElMessage.error(error?.message || '导入字段失败')
  } finally {
    importing.value = false
  }
}

async function openEditor(row) {
  if (!row.fieldId) {
    ElMessage.warning('请先导入该数据集的字段')
    return
  }
  try {
    const field = await getField(row.fieldId)
    editingRow.value = row
    Object.assign(form, {
      name: field.name,
      displayName: field.displayName || '',
      dataType: field.dataType || 'STRING',
      semanticRole: field.semanticRole || '',
      sourceTable: field.sourceTable || '',
      description: field.description || '',
      status: field.status || 'ACTIVE',
    })
    editVisible.value = true
  } catch (error) {
    ElMessage.error(error?.message || '读取字段定义失败')
  }
}

async function saveField() {
  if (!editingRow.value?.fieldId || saving.value) {
    return
  }
  if (!form.name.trim()) {
    ElMessage.warning('字段名不能为空')
    return
  }
  saving.value = true
  try {
    await updateField(editingRow.value.fieldId, {
      name: form.name.trim(),
      displayName: form.displayName.trim() || null,
      dataType: form.dataType,
      semanticRole: form.semanticRole.trim() || null,
      sourceTable: form.sourceTable.trim() || null,
      description: form.description.trim() || null,
      status: form.status,
    })
    editVisible.value = false
    await loadCatalog()
    ElMessage.success('字段定义已更新')
  } catch (error) {
    ElMessage.error(error?.message || '保存字段失败')
  } finally {
    saving.value = false
  }
}

function goToRules() {
  router.push('/rules')
}
</script>

<template>
  <div class="page-content">
    <el-breadcrumb separator="/" class="page-breadcrumb">
      <el-breadcrumb-item>工作台</el-breadcrumb-item>
      <el-breadcrumb-item>字段库</el-breadcrumb-item>
    </el-breadcrumb>

    <header class="page-header rule-page-header">
      <div>
        <h1>字段库</h1>
        <p>从数据集导入物理字段，并维护业务字段名、类型和语义角色。</p>
      </div>
      <el-button type="primary" plain @click="goToRules">去编排规则</el-button>
    </header>

    <el-card class="list-card rule-toolbar-card" shadow="never">
      <div class="rule-toolbar">
        <el-select
          v-model="selectedDatasetId"
          class="dataset-select"
          placeholder="选择数据集"
          :loading="loadingDatasets"
          filterable
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

        <el-input
          v-model="keyword"
          class="rule-search"
          placeholder="搜索表、字段或类型"
          clearable
          :prefix-icon="Search"
        />

        <div class="rule-toolbar-spacer"></div>

        <el-button :icon="Refresh" :loading="loadingCatalog" @click="loadCatalog">刷新</el-button>
        <el-button
          type="primary"
          :icon="Download"
          :loading="importing"
          :disabled="!selectedDatasetId"
          @click="handleImport"
        >
          导入字段
        </el-button>
      </div>

      <div v-if="selectedDataset" class="selection-summary">
        <span>当前数据集：<strong>{{ selectedDataset.name }}</strong></span>
        <span>{{ catalog.length }} 个字段</span>
        <span>已导入 {{ importedCount }} 个</span>
      </div>
    </el-card>

    <el-card class="list-card rule-grid-card" shadow="never">
      <el-table
        v-loading="loadingCatalog"
        :data="filteredCatalog"
        row-key="columnId"
        height="520"
        empty-text="暂无字段，请先上传数据集并导入字段"
      >
        <el-table-column prop="datasetName" label="数据集" min-width="170" show-overflow-tooltip />
        <el-table-column prop="tableName" label="数据表" min-width="190" show-overflow-tooltip />
        <el-table-column prop="columnName" label="物理字段" min-width="180" show-overflow-tooltip />
        <el-table-column label="逻辑字段" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.fieldName">{{ row.fieldName }}</span>
            <el-tag v-else size="small" type="info">未导入</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="fieldDisplayName" label="显示名" min-width="190" show-overflow-tooltip />
        <el-table-column prop="dataType" label="类型" width="120">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.dataType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :icon="EditPen"
              :disabled="!row.fieldId"
              @click="openEditor(row)"
            >
              编辑
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="editVisible" title="编辑字段定义" width="560px">
      <el-form label-width="88px" label-position="left">
        <el-form-item label="字段名" required>
          <el-input v-model="form.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="form.displayName" maxlength="255" />
        </el-form-item>
        <el-form-item label="数据类型">
          <el-select v-model="form.dataType" class="full-width">
            <el-option v-for="type in dataTypes" :key="type" :label="type" :value="type" />
          </el-select>
        </el-form-item>
        <el-form-item label="语义角色">
          <el-input v-model="form.semanticRole" placeholder="例如 ORDER_ID、DELIVERY_DATE" />
        </el-form-item>
        <el-form-item label="来源">
          <el-input v-model="form.sourceTable" maxlength="255" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="1000" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio v-for="status in fieldStatuses" :key="status" :value="status">
              {{ status === 'ACTIVE' ? '启用' : '归档' }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveField">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.rule-page-header {
  align-items: flex-end;
}

.rule-toolbar-card {
  margin-bottom: 14px;
}

.rule-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.dataset-select {
  width: 280px;
}

.rule-search {
  width: 250px;
}

.rule-toolbar-spacer {
  flex: 1;
}

.selection-summary {
  display: flex;
  gap: 18px;
  margin-top: 12px;
  color: #60756b;
  font-size: 12px;
}

.selection-summary strong {
  color: #1d3d32;
}

.option-meta {
  float: right;
  margin-left: 20px;
  color: #8a9b93;
  font-size: 11px;
}

.full-width {
  width: 100%;
}
</style>
