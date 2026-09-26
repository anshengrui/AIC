<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  imageUrl: string
  bbox?: [number, number, number, number]
  targetLabel?: string
  highContrast: boolean
}>()

const boxStyle = computed(() => {
  if (!props.bbox) return undefined
  const [x1, y1, x2, y2] = props.bbox
  return {
    left: `${x1 * 100}%`,
    top: `${y1 * 100}%`,
    width: `${(x2 - x1) * 100}%`,
    height: `${(y2 - y1) * 100}%`,
  }
})
</script>

<template>
  <div class="phone-shell" :class="{ 'phone-shell--contrast': highContrast }">
    <div v-if="imageUrl" class="screen-stage">
      <img :src="imageUrl" alt="当前手机页面截图" class="screen-image" />
      <div v-if="bbox" class="target-box" :style="boxStyle" aria-live="polite">
        <span class="target-label">下一步 · {{ targetLabel }}</span>
      </div>
    </div>
    <div v-else class="screen-placeholder">
      <span class="placeholder-icon" aria-hidden="true">▣</span>
      <strong>截图预览区</strong>
      <p>上传 PNG/JPG，或载入内置演示图</p>
    </div>
  </div>
</template>

