<script setup>
import { computed } from 'vue'
import { Delete, Plus } from '@element-plus/icons-vue'
import DerivePredicateEditor from '@/components/DerivePredicateEditor.vue'

defineOptions({ name: 'DeriveExpressionEditor' })

const props = defineProps({
  modelValue: {
    type: Object,
    default: () => ({}),
  },
  columns: {
    type: Array,
    default: () => [],
  },
  inputAlias: {
    type: String,
    default: 'input',
  },
  depth: {
    type: Number,
    default: 0,
  },
})

const emit = defineEmits(['update:modelValue'])

const expressionKinds = [
  { id: 'FIELD', label: '字段' },
  { id: 'LITERAL', label: '常量' },
  { id: 'DATE_DIFF_DAYS', label: '日期相差天数' },
  { id: 'DATE_PART', label: '提取日期部分' },
  { id: 'ARITHMETIC', label: '数值运算' },
  { id: 'CONCAT', label: '文本拼接' },
  { id: 'COALESCE', label: '空值合并' },
  { id: 'CASE_WHEN', label: '条件分支' },
  { id: 'DISTANCE_HAVERSINE', label: '经纬度距离' },
]
const expressionKindsForDepth = computed(() => (
  props.depth >= 20
    ? expressionKinds.filter((kind) => ['FIELD', 'LITERAL'].includes(kind.id))
    : expressionKinds
))

const literalTypes = [
  { id: 'STRING', label: '文本' },
  { id: 'INTEGER', label: '整数' },
  { id: 'DECIMAL', label: '小数' },
  { id: 'BOOLEAN', label: '布尔值' },
  { id: 'DATE', label: '日期' },
  { id: 'DATETIME', label: '日期时间' },
]

const dateParts = [
  { id: 'YEAR', label: '年份' },
  { id: 'QUARTER', label: '季度' },
  { id: 'MONTH', label: '月份' },
  { id: 'WEEK', label: '周' },
  { id: 'DAY', label: '日' },
  { id: 'DAY_OF_WEEK', label: '星期' },
  { id: 'DAY_OF_YEAR', label: '年内天数' },
  { id: 'HOUR', label: '小时' },
  { id: 'MINUTE', label: '分钟' },
  { id: 'SECOND', label: '秒' },
]

const arithmeticOperators = [
  { id: 'ADD', label: '加' },
  { id: 'SUBTRACT', label: '减' },
  { id: 'MULTIPLY', label: '乘' },
  { id: 'DIVIDE', label: '除' },
  { id: 'MODULO', label: '取模' },
  { id: 'POWER', label: '幂' },
]

const currentKind = computed(() => props.modelValue?.op || props.modelValue?.type || 'FIELD')
const argumentsList = computed(() => Array.isArray(props.modelValue?.args) ? props.modelValue.args : [])
const branches = computed(() => Array.isArray(props.modelValue?.branches) ? props.modelValue.branches : [])

function emitValue(value) {
  emit('update:modelValue', value)
}

function patchValue(key, value) {
  emitValue({ ...props.modelValue, [key]: value })
}

function columnById(columnId) {
  return props.columns.find((column) => column.columnId === columnId)
}

function fieldReference(columnId) {
  const column = columnById(columnId)
  if (!column) return { type: 'FIELD', nameSnapshot: '' }
  const reference = {
    type: 'FIELD',
    nameSnapshot: column.name,
    tableRef: {
      alias: props.inputAlias || 'input',
      ...(column.sourceTableId ? { tableId: column.sourceTableId } : {}),
    },
  }
  if (!column.synthetic) reference.columnId = column.columnId
  return reference
}

function firstField() {
  return fieldReference(props.columns[0]?.columnId)
}

function literal(dataType = 'STRING', value = '') {
  return { type: 'LITERAL', dataType, value }
}

function defaultExpression(kind) {
  switch (kind) {
    case 'LITERAL':
      return literal()
    case 'DATE_DIFF_DAYS':
      return { op: kind, left: firstField(), right: firstField() }
    case 'DATE_PART':
      return { op: kind, part: 'YEAR', value: firstField() }
    case 'ARITHMETIC':
      return { op: kind, operator: 'ADD', left: firstField(), right: literal('DECIMAL', 0) }
    case 'CONCAT':
      return { op: kind, args: [firstField(), literal()] }
    case 'COALESCE':
      return { op: kind, args: [firstField(), literal()] }
    case 'CASE_WHEN':
      return {
        op: kind,
        branches: [{ when: defaultPredicate(), then: literal() }],
        else: literal(),
      }
    case 'DISTANCE_HAVERSINE':
      return {
        op: kind,
        longitude1: firstField(),
        latitude1: firstField(),
        longitude2: firstField(),
        latitude2: firstField(),
      }
    default:
      return firstField()
  }
}

function defaultPredicate() {
  const field = firstField()
  return { op: 'IS_NULL', field, args: [field] }
}

function changeKind(kind) {
  emitValue(defaultExpression(kind))
}

function fieldColumnId(expression) {
  return expression?.columnId || (expression?.nameSnapshot ? `name:${expression.nameSnapshot}` : '')
}

function updateLiteralType(dataType) {
  let value = ''
  if (dataType === 'INTEGER' || dataType === 'DECIMAL') value = 0
  if (dataType === 'BOOLEAN') value = false
  emitValue({ type: 'LITERAL', dataType, value })
}

function updateArgument(index, value) {
  const args = [...argumentsList.value]
  args[index] = value
  patchValue('args', args)
}

function addArgument() {
  if (argumentsList.value.length >= 50) return
  patchValue('args', [...argumentsList.value, literal()])
}

function removeArgument(index) {
  if (argumentsList.value.length <= 2) return
  patchValue('args', argumentsList.value.filter((_, itemIndex) => itemIndex !== index))
}

function updateBranch(index, key, value) {
  const nextBranches = branches.value.map((branch, branchIndex) => (
    branchIndex === index ? { ...branch, [key]: value } : branch
  ))
  patchValue('branches', nextBranches)
}

function addBranch() {
  if (branches.value.length >= 50) return
  patchValue('branches', [...branches.value, { when: defaultPredicate(), then: literal() }])
}

function removeBranch(index) {
  if (branches.value.length <= 1) return
  patchValue('branches', branches.value.filter((_, branchIndex) => branchIndex !== index))
}
</script>

<template>
  <div class="expression-editor" :class="{ 'is-nested': depth > 0 }">
    <div class="expression-toolbar">
      <span class="expression-caption">{{ depth ? '子表达式' : '计算方式' }}</span>
      <el-select :model-value="currentKind" class="expression-kind" @update:model-value="changeKind">
        <el-option v-for="kind in expressionKindsForDepth" :key="kind.id" :label="kind.label" :value="kind.id" />
      </el-select>
    </div>

    <div v-if="currentKind === 'FIELD'" class="expression-body expression-inline">
      <el-select
        :model-value="fieldColumnId(modelValue)"
        class="expression-wide"
        filterable
        placeholder="选择字段"
        @update:model-value="emitValue(fieldReference($event))"
      >
        <el-option v-for="column in columns" :key="column.columnId" :label="column.name" :value="column.columnId">
          <span class="column-option" :title="column.name">
            <strong>{{ column.name }}</strong>
            <small>{{ column.type || column.dataType || '字段' }}</small>
          </span>
        </el-option>
      </el-select>
    </div>

    <div v-else-if="currentKind === 'LITERAL'" class="expression-body expression-inline">
      <el-select :model-value="modelValue.dataType || 'STRING'" class="literal-type" @update:model-value="updateLiteralType">
        <el-option v-for="type in literalTypes" :key="type.id" :label="type.label" :value="type.id" />
      </el-select>
      <el-switch
        v-if="modelValue.dataType === 'BOOLEAN'"
        :model-value="Boolean(modelValue.value)"
        inline-prompt
        active-text="真"
        inactive-text="假"
        @update:model-value="patchValue('value', $event)"
      />
      <el-input-number
        v-else-if="['INTEGER', 'DECIMAL'].includes(modelValue.dataType)"
        :model-value="Number(modelValue.value || 0)"
        class="expression-wide"
        :precision="modelValue.dataType === 'INTEGER' ? 0 : undefined"
        controls-position="right"
        @update:model-value="patchValue('value', $event)"
      />
      <el-date-picker
        v-else-if="modelValue.dataType === 'DATE'"
        :model-value="modelValue.value"
        class="expression-wide"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="选择日期"
        @update:model-value="patchValue('value', $event)"
      />
      <el-date-picker
        v-else-if="modelValue.dataType === 'DATETIME'"
        :model-value="modelValue.value"
        class="expression-wide"
        type="datetime"
        value-format="YYYY-MM-DD HH:mm:ss"
        placeholder="选择日期时间"
        @update:model-value="patchValue('value', $event)"
      />
      <el-input
        v-else
        :model-value="modelValue.value"
        class="expression-wide"
        maxlength="500"
        placeholder="输入常量"
        @update:model-value="patchValue('value', $event)"
      />
    </div>

    <div v-else-if="currentKind === 'DATE_DIFF_DAYS'" class="expression-body expression-grid">
      <div class="expression-slot">
        <span>被减日期（左）</span>
        <DeriveExpressionEditor :model-value="modelValue.left" :columns="columns" :input-alias="inputAlias" :depth="depth + 1" @update:model-value="patchValue('left', $event)" />
      </div>
      <div class="expression-slot">
        <span>减去日期（右）</span>
        <DeriveExpressionEditor :model-value="modelValue.right" :columns="columns" :input-alias="inputAlias" :depth="depth + 1" @update:model-value="patchValue('right', $event)" />
      </div>
    </div>

    <div v-else-if="currentKind === 'DATE_PART'" class="expression-body expression-stack">
      <el-select :model-value="modelValue.part || 'YEAR'" class="date-part" @update:model-value="patchValue('part', $event)">
        <el-option v-for="part in dateParts" :key="part.id" :label="part.label" :value="part.id" />
      </el-select>
      <DeriveExpressionEditor :model-value="modelValue.value" :columns="columns" :input-alias="inputAlias" :depth="depth + 1" @update:model-value="patchValue('value', $event)" />
    </div>

    <div v-else-if="currentKind === 'ARITHMETIC'" class="expression-body expression-stack">
      <el-select :model-value="modelValue.operator || 'ADD'" class="arithmetic-operator" @update:model-value="patchValue('operator', $event)">
        <el-option v-for="operator in arithmeticOperators" :key="operator.id" :label="operator.label" :value="operator.id" />
      </el-select>
      <div class="expression-grid">
        <div class="expression-slot">
          <span>左值</span>
          <DeriveExpressionEditor :model-value="modelValue.left" :columns="columns" :input-alias="inputAlias" :depth="depth + 1" @update:model-value="patchValue('left', $event)" />
        </div>
        <div class="expression-slot">
          <span>右值</span>
          <DeriveExpressionEditor :model-value="modelValue.right" :columns="columns" :input-alias="inputAlias" :depth="depth + 1" @update:model-value="patchValue('right', $event)" />
        </div>
      </div>
    </div>

    <div v-else-if="['CONCAT', 'COALESCE'].includes(currentKind)" class="expression-body expression-stack">
      <div v-for="(argument, index) in argumentsList" :key="index" class="argument-row">
        <span class="argument-index">{{ index + 1 }}</span>
        <DeriveExpressionEditor class="argument-editor" :model-value="argument" :columns="columns" :input-alias="inputAlias" :depth="depth + 1" @update:model-value="updateArgument(index, $event)" />
        <el-button v-if="argumentsList.length > 2" link type="danger" :icon="Delete" aria-label="移除参数" @click="removeArgument(index)" />
      </div>
      <el-button link type="primary" :icon="Plus" :disabled="argumentsList.length >= 50" @click="addArgument">添加参数</el-button>
    </div>

    <div v-else-if="currentKind === 'CASE_WHEN'" class="expression-body expression-stack">
      <div v-for="(branch, index) in branches" :key="index" class="branch-card">
        <div class="branch-head">
          <strong>条件 {{ index + 1 }}</strong>
          <el-button v-if="branches.length > 1" link type="danger" :icon="Delete" @click="removeBranch(index)">移除</el-button>
        </div>
        <DerivePredicateEditor
          :model-value="branch.when"
          :columns="columns"
          :input-alias="inputAlias"
          @update:model-value="updateBranch(index, 'when', $event)"
        />
        <div class="branch-result">
          <span>满足条件时</span>
          <DeriveExpressionEditor :model-value="branch.then" :columns="columns" :input-alias="inputAlias" :depth="depth + 1" @update:model-value="updateBranch(index, 'then', $event)" />
        </div>
      </div>
      <el-button link type="primary" :icon="Plus" :disabled="branches.length >= 50" @click="addBranch">添加条件分支</el-button>
      <div class="branch-result fallback-result">
        <span>其他情况</span>
        <DeriveExpressionEditor :model-value="modelValue.else" :columns="columns" :input-alias="inputAlias" :depth="depth + 1" @update:model-value="patchValue('else', $event)" />
      </div>
    </div>

    <div v-else-if="currentKind === 'DISTANCE_HAVERSINE'" class="expression-body distance-grid">
      <div v-for="item in [
        { key: 'longitude1', label: '起点经度' },
        { key: 'latitude1', label: '起点纬度' },
        { key: 'longitude2', label: '终点经度' },
        { key: 'latitude2', label: '终点纬度' },
      ]" :key="item.key" class="expression-slot">
        <span>{{ item.label }}</span>
        <DeriveExpressionEditor :model-value="modelValue[item.key]" :columns="columns" :input-alias="inputAlias" :depth="depth + 1" @update:model-value="patchValue(item.key, $event)" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.expression-editor {
  min-width: 0;
  padding: 10px;
  background: #fff;
  border: 1px solid var(--line-strong);
  border-radius: 8px;
}

.expression-editor.is-nested {
  background: rgba(23, 107, 87, 0.025);
}

.expression-toolbar,
.expression-inline,
.predicate-row,
.branch-head,
.argument-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.expression-toolbar {
  margin-bottom: 8px;
}

.expression-caption,
.expression-slot > span,
.branch-result > span {
  flex: 0 0 auto;
  color: var(--ink-faint);
  font-size: 11px;
}

.expression-kind {
  width: 170px;
}

.expression-body,
.expression-wide,
.argument-editor,
.branch-result > .expression-editor {
  min-width: 0;
  width: 100%;
}

.expression-grid,
.distance-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.expression-stack,
.expression-slot,
.branch-card,
.branch-result {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.literal-type,
.date-part,
.arithmetic-operator,
.predicate-op {
  width: 150px;
  flex: 0 0 150px;
}

.column-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.column-option strong,
.column-option small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.column-option strong {
  min-width: 0;
  font-weight: 500;
}

.column-option small {
  max-width: 34%;
  color: var(--ink-faint);
  font-size: 11px;
}

.argument-row {
  align-items: flex-start;
}

.argument-index {
  display: inline-grid;
  width: 22px;
  height: 22px;
  flex: 0 0 22px;
  place-items: center;
  margin-top: 8px;
  color: #176b57;
  font-size: 11px;
  background: rgba(23, 107, 87, 0.09);
  border-radius: 50%;
}

.branch-card {
  padding: 10px;
  background: rgba(23, 107, 87, 0.025);
  border: 1px dashed var(--line-strong);
  border-radius: 8px;
}

.branch-head {
  justify-content: space-between;
}

.branch-head strong {
  color: var(--ink-soft);
  font-size: 12px;
}

.predicate-field,
.predicate-value {
  min-width: 140px;
  flex: 1 1 170px;
}

.fallback-result {
  padding-top: 8px;
  border-top: 1px dashed var(--line-strong);
}

@media (max-width: 900px) {
  .expression-grid,
  .distance-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .expression-inline,
  .predicate-row {
    align-items: stretch;
    flex-direction: column;
  }

  .expression-kind,
  .literal-type,
  .date-part,
  .arithmetic-operator,
  .predicate-field,
  .predicate-op,
  .predicate-value {
    width: 100%;
    max-width: none;
    flex-basis: auto;
  }
}
</style>
