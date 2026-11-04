<script setup>
import { computed } from 'vue'
import { Delete, Plus } from '@element-plus/icons-vue'

defineOptions({ name: 'DerivePredicateEditor' })

const props = defineProps({
  modelValue: { type: Object, default: () => ({ op: 'IS_NULL' }) },
  columns: { type: Array, default: () => [] },
  inputAlias: { type: String, default: 'input' },
  depth: { type: Number, default: 0 },
})

const emit = defineEmits(['update:modelValue'])

const logicalOperators = [
  { id: 'AND', label: '同时满足' },
  { id: 'OR', label: '任一满足' },
  { id: 'NOT', label: '条件取反' },
]

const leafOperators = [
  { id: 'IS_NULL', label: '为空' },
  { id: 'IS_EMPTY', label: '为空字符串' },
  { id: 'IS_BLANK', label: '为空白' },
  { id: 'EQ', label: '等于' },
  { id: 'NE', label: '不等于' },
  { id: 'GT', label: '大于' },
  { id: 'GTE', label: '大于等于' },
  { id: 'LT', label: '小于' },
  { id: 'LTE', label: '小于等于' },
  { id: 'BETWEEN', label: '介于' },
  { id: 'IN', label: '属于集合' },
  { id: 'NOT_IN', label: '不属于集合' },
  { id: 'REGEX_MATCH', label: '匹配正则' },
  { id: 'DATE_PARSEABLE', label: '可解析为日期' },
]

const op = computed(() => String(props.modelValue?.op || 'IS_NULL').toUpperCase())
const isGroup = computed(() => ['AND', 'OR'].includes(op.value))
const isNot = computed(() => op.value === 'NOT')
const predicates = computed(() => Array.isArray(props.modelValue?.predicates) ? props.modelValue.predicates : [])
const needsValue = computed(() => ![
  'IS_NULL', 'IS_EMPTY', 'IS_BLANK', 'DATE_PARSEABLE', 'AND', 'OR', 'NOT',
].includes(op.value))
const usesValueSet = computed(() => ['IN', 'NOT_IN'].includes(op.value))

function update(value) {
  emit('update:modelValue', value)
}

function fieldReference(columnId) {
  const column = props.columns.find((item) => item.columnId === columnId)
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

function fieldId(predicate = props.modelValue) {
  const field = predicate?.field || predicate?.args?.[0]
  return field?.columnId || (field?.nameSnapshot ? `name:${field.nameSnapshot}` : '')
}

function updateLeaf(patch) {
  update({ ...props.modelValue, ...patch })
}

function changeOperator(nextOp) {
  if (['AND', 'OR'].includes(nextOp)) {
    update({ op: nextOp, predicates: [{ op: 'IS_NULL', field: fieldReference(props.columns[0]?.columnId) }] })
    return
  }
  if (nextOp === 'NOT') {
    update({ op: 'NOT', predicate: { op: 'IS_NULL', field: fieldReference(props.columns[0]?.columnId) } })
    return
  }
  const field = fieldReference(props.columns[0]?.columnId)
  const value = ['IN', 'NOT_IN'].includes(nextOp) ? [] : undefined
  update({ op: nextOp, field, args: [field], ...(value ? { value } : {}) })
}

function updateField(columnId) {
  const field = fieldReference(columnId)
  updateLeaf({ field, args: [field] })
}

function updateChild(index, value) {
  if (isNot.value) {
    update({ ...props.modelValue, predicate: value })
    return
  }
  const next = [...predicates.value]
  next[index] = value
  update({ ...props.modelValue, predicates: next })
}

function addChild() {
  if (predicates.value.length >= 50) return
  update({
    ...props.modelValue,
    predicates: [...predicates.value, { op: 'IS_NULL', field: fieldReference(props.columns[0]?.columnId) }],
  })
}

function removeChild(index) {
  if (predicates.value.length <= 1) return
  update({ ...props.modelValue, predicates: predicates.value.filter((_, itemIndex) => itemIndex !== index) })
}
</script>

<template>
  <div class="predicate-editor" :class="{ 'is-nested': depth > 0 }">
    <div class="predicate-toolbar">
      <span class="predicate-caption">{{ depth ? '子条件' : '判断方式' }}</span>
      <el-select :model-value="op" class="predicate-operator" @update:model-value="changeOperator">
        <el-option-group v-if="depth < 24" label="组合条件">
          <el-option v-for="operator in logicalOperators" :key="operator.id" :label="operator.label" :value="operator.id" />
        </el-option-group>
        <el-option-group label="字段条件">
          <el-option v-for="operator in leafOperators" :key="operator.id" :label="operator.label" :value="operator.id" />
        </el-option-group>
      </el-select>
    </div>

    <div v-if="isGroup" class="predicate-children">
      <div v-for="(predicate, index) in predicates" :key="index" class="predicate-child-row">
        <DerivePredicateEditor
          class="predicate-child"
          :model-value="predicate"
          :columns="columns"
          :input-alias="inputAlias"
          :depth="depth + 1"
          @update:model-value="updateChild(index, $event)"
        />
        <el-button v-if="predicates.length > 1" link type="danger" :icon="Delete" aria-label="移除条件" @click="removeChild(index)" />
      </div>
      <el-button link type="primary" :icon="Plus" :disabled="predicates.length >= 50 || depth >= 24" @click="addChild">添加条件</el-button>
    </div>

    <div v-else-if="isNot" class="predicate-children">
      <DerivePredicateEditor
        :model-value="modelValue.predicate"
        :columns="columns"
        :input-alias="inputAlias"
        :depth="depth + 1"
        @update:model-value="updateChild(0, $event)"
      />
    </div>

    <div v-else class="predicate-leaf">
      <el-select :model-value="fieldId()" class="predicate-field" filterable placeholder="选择字段" @update:model-value="updateField">
        <el-option v-for="column in columns" :key="column.columnId" :label="column.name" :value="column.columnId" />
      </el-select>
      <el-select v-if="usesValueSet" :model-value="modelValue.value || []" class="predicate-value" multiple filterable allow-create default-first-option collapse-tags placeholder="输入并回车添加值" @update:model-value="updateLeaf({ value: $event })" />
      <el-input v-else-if="needsValue" :model-value="modelValue.value" class="predicate-value" :placeholder="op === 'REGEX_MATCH' ? '输入匹配规则' : '比较值'" @update:model-value="updateLeaf({ value: $event })" />
      <el-input v-if="op === 'BETWEEN'" :model-value="modelValue.upperValue" class="predicate-value" placeholder="上限" @update:model-value="updateLeaf({ upperValue: $event })" />
    </div>
  </div>
</template>

<style scoped>
.predicate-editor {
  display: grid;
  gap: 8px;
  min-width: 0;
  padding: 9px;
  background: #fff;
  border: 1px solid var(--line-strong);
  border-radius: 8px;
}

.predicate-editor.is-nested {
  background: rgba(23, 107, 87, 0.025);
}

.predicate-toolbar,
.predicate-leaf,
.predicate-child-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.predicate-caption {
  flex: 0 0 auto;
  color: var(--ink-faint);
  font-size: 11px;
}

.predicate-operator {
  width: 190px;
  max-width: 100%;
}

.predicate-children {
  display: grid;
  gap: 8px;
  min-width: 0;
  padding-left: 10px;
  border-left: 2px solid rgba(23, 107, 87, 0.15);
}

.predicate-child,
.predicate-value {
  min-width: 0;
  flex: 1 1 180px;
}

.predicate-field {
  min-width: 160px;
  flex: 1 1 220px;
}

@media (max-width: 900px) {
  .predicate-toolbar,
  .predicate-leaf {
    align-items: stretch;
    flex-direction: column;
  }

  .predicate-operator,
  .predicate-field,
  .predicate-value {
    width: 100%;
    max-width: none;
    flex-basis: auto;
  }
}
</style>
