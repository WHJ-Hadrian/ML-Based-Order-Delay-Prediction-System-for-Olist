<script setup>
import { ref } from 'vue'
import { Plus } from '@element-plus/icons-vue'

const props = defineProps({
  compact: { type: Boolean, default: false },
  uploading: { type: Boolean, default: false },
  progress: { type: Number, default: 0 },
  disabled: { type: Boolean, default: false },
})

const emit = defineEmits(['select'])

const inputRef = ref(null)
const dragActive = ref(false)

function openPicker() {
  if (props.uploading || props.disabled) {
    return
  }
  inputRef.value?.click()
}

function selectFile(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (file) {
    emit('select', file)
  }
}

function onDrop(event) {
  dragActive.value = false
  if (props.uploading || props.disabled) {
    return
  }
  const file = event.dataTransfer?.files?.[0]
  if (file) {
    emit('select', file)
  }
}
</script>

<template>
  <div class="dataset-uploader" @click.stop>
    <input
      ref="inputRef"
      class="file-input"
      type="file"
      accept=".csv,.tsv,.zip,.xlsx"
      @change="selectFile"
    />

    <template v-if="compact">
      <button
        type="button"
        class="compact-upload"
        :class="{ 'is-uploading': uploading }"
        :disabled="uploading || disabled"
        @click="openPicker"
      >
        <el-icon><Plus /></el-icon>
        <span>{{ uploading ? `正在上传 ${progress}%` : '上传数据' }}</span>
      </button>
      <el-progress
        v-if="uploading"
        class="compact-progress"
        :percentage="progress"
        :show-text="false"
        :stroke-width="3"
      />
    </template>

    <button
      v-else
      type="button"
      class="empty-upload"
      :class="{ 'is-dragging': dragActive, 'is-uploading': uploading }"
      :disabled="uploading || disabled"
      @click="openPicker"
      @dragenter.prevent="dragActive = true"
      @dragover.prevent="dragActive = true"
      @dragleave.prevent="dragActive = false"
      @drop.prevent.stop="onDrop"
    >
      <span class="upload-plus">+</span>
      <span class="upload-title">{{ uploading ? '正在上传数据' : '上传数据' }}</span>
      <span class="upload-hint">
        {{ uploading ? `${progress}%` : '点击选择，或把文件拖到这里' }}
      </span>
      <span class="upload-formats">CSV · TSV · ZIP · XLSX，单文件最大 1 GB</span>
      <el-progress
        v-if="uploading"
        class="empty-progress"
        :percentage="progress"
        :stroke-width="6"
        striped
        striped-flow
      />
    </button>
  </div>
</template>
