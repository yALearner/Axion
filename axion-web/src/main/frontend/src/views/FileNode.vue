<script setup>
// 递归文件树节点（012 工作区文件浏览）：目录可展开、文件点击 → open 事件（SFC 自引用递归）。
defineProps({ node: { type: Object, required: true } })
defineEmits(['open'])
const expanded = ref(false)
import { ref } from 'vue'
</script>

<template>
  <div class="tree-node">
    <div v-if="node.type === 'dir'" class="tree-dir" @click="expanded = !expanded">
      {{ expanded ? '▾' : '▸' }} {{ node.name }}/
    </div>
    <div v-else class="tree-file mono" @click="$emit('open', node.path)">{{ node.name }}</div>
    <div v-if="expanded" class="tree-children">
      <FileNode v-for="child in node.children" :key="child.path" :node="child" @open="$emit('open', $event)" />
    </div>
  </div>
</template>

<style scoped>
.tree-dir { cursor: pointer; color: var(--text); padding: 3px 0; font-size: 13px; }
.tree-file { cursor: pointer; color: #7dd3fc; padding: 3px 0; font-size: 13px; }
.tree-children { margin-left: 14px; }
</style>
