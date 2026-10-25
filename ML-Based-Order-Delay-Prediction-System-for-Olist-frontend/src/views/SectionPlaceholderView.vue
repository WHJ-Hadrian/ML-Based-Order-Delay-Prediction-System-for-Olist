<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { findNavigationItem } from '@/config/navigation'

const route = useRoute()
const section = computed(() => findNavigationItem(route.meta.navigationKey))
</script>

<template>
  <div class="page-content">
    <el-breadcrumb separator="/" class="page-breadcrumb">
      <el-breadcrumb-item>工作台</el-breadcrumb-item>
      <el-breadcrumb-item>{{ section.title }}</el-breadcrumb-item>
    </el-breadcrumb>

    <header class="page-header">
      <div>
        <h1>{{ section.title }}</h1>
        <p>{{ section.description }}</p>
      </div>
    </header>

    <el-card class="list-card section-placeholder-card" shadow="never">
      <el-empty :image-size="112" :description="section.emptyText" />
    </el-card>
  </div>
</template>
