<script setup lang=ts>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import PhonePreview from './components/PhonePreview.vue'
import { analyzeScreenshot, confirmRisk, createSession, getHealth, submitFeedback } from './services/api'
import type { AnalysisResponse, AssistanceMode, FeedbackType, SessionView } from './types'

const taskText = ref('帮我关闭自动续费')
const mode = ref<AssistanceMode>('standard')
const imageFile = ref<File | null>(null)
const imageUrl = ref('')
const session = ref<SessionView | null>(null)
const analysis = ref<AnalysisResponse | null>(null)
const guidance = ref('载入演示图或上传截图后，开始第一步。')
const errorMessage = ref('')
const loading = ref(false)
const modelMode = ref(false)
const modelName = ref<string | null>(null)

const modeOptions: Array<{ value: AssistanceMode; label: string; hint: string }> = [
  { value: 'standard', label: '普通', hint: '简洁说明' },
  { value: 'senior', label: '老年简易', hint: '大字慢节奏' },
  { value: 'low_vision_voice', label: '低视力 / 语音', hint: '高对比定位' },
]
const targetElement = computed(() => {
  const targetId = analysis.value?.recommended_action.element_id
  return analysis.value?.elements.find((element) => element.id === targetId)
})
const highContrast = computed(() => mode.value === 'low_vision_voice')
const appClass = computed(() => ({ 'app--senior': mode.value === 'senior', 'app--contrast': highContrast.value }))
const needsNewSession = computed(
  () =>
    !session.value ||
    session.value.state === 'COMPLETED' ||
    session.value.state === 'BLOCKED' ||
    session.value.task_text !== taskText.value.trim() ||
    session.value.mode !== mode.value,
)
const ready = computed(() => Boolean(taskText.value.trim() && imageFile.value && !loading.value))

function setPreview(file: File) {
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value)
  imageFile.value = file
  imageUrl.value = URL.createObjectURL(file)
  analysis.value = null
  errorMessage.value = ''
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (!['image/png', 'image/jpeg'].includes(file.type)) {
    errorMessage.value = '请选择 PNG 或 JPG 图片。'
    input.value = ''
    return
  }
  setPreview(file)
}

async function svgToPngFile(source: string): Promise<File> {
  const response = await fetch(source)
  const svgBlob = await response.blob()
  const sourceUrl = URL.createObjectURL(svgBlob)
  try {
    const image = new Image()
    image.src = sourceUrl
    await image.decode()
    const canvas = document.createElement('canvas')
    canvas.width = 390
    canvas.height = 844
    const context = canvas.getContext('2d')
    if (!context) throw new Error('浏览器无法创建图片画布')
    context.drawImage(image, 0, 0, canvas.width, canvas.height)
    const pngBlob = await new Promise<Blob>((resolve, reject) => {
      canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error('演示图转换失败'))), 'image/png')
    })
    return new File([pngBlob], 'easyaccess-profile-center.png', { type: 'image/png' })
  } finally {
    URL.revokeObjectURL(sourceUrl)
  }
}

async function loadDemo() {
  loading.value = true
  errorMessage.value = ''
  try {
    setPreview(await svgToPngFile('/mock-screens/profile-center.svg'))
    guidance.value = '演示图已载入。点击“开始识别”查看结构化结果和坐标高亮。'
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '演示图加载失败'
  } finally {
    loading.value = false
  }
}

async function runAnalysis() {
  if (!ready.value || !imageFile.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    if (needsNewSession.value) session.value = await createSession(taskText.value.trim(), mode.value)
    analysis.value = await analyzeScreenshot(session.value!.session_id, imageFile.value)
    guidance.value = analysis.value.recommended_action.instruction
    session.value = {
      ...session.value!,
      state: analysis.value.risk.confirmation_required ? 'WAITING_CONFIRMATION' : 'WAITING_FEEDBACK',
      task_status: analysis.value.task_status,
      latest_analysis: analysis.value,
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '分析失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

async function sendFeedback(feedback: FeedbackType) {
  if (!session.value || loading.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await submitFeedback(session.value.session_id, feedback)
    session.value = response.session
    guidance.value = response.guidance
    analysis.value = response.session.latest_analysis
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '反馈提交失败。'
  } finally {
    loading.value = false
  }
}

async function handleRisk(approved: boolean) {
  if (!session.value || loading.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await confirmRisk(session.value.session_id, approved)
    session.value = response.session
    guidance.value = response.guidance
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '风险确认失败。'
  } finally {
    loading.value = false
  }
}

onBeforeUnmount(() => {
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value)
})

onMounted(async () => {
  try {
    const health = await getHealth()
    modelMode.value = health.analysis_mode === 'model' && health.model_configured
    modelName.value = health.model_name
  } catch {
    // The main workflow displays API failures; keep a quiet offline badge here.
  }
})
</script>

<template>
  <main class=app :class=appClass>
    <header class="topbar">
      <a class="brand" href="#top" aria-label="EasyAccess 首页">
        <span class="brand-mark" aria-hidden="true">E</span>
        <span><strong>EasyAccess</strong><small>易屏 · 数字界面导航员</small></span>
      </a>
      <span class="mock-badge"><i aria-hidden="true"></i>{{ modelMode ? `多模态 AI · ${modelName}` : '离线 Mock 模式' }}</span>
    </header>
    <section id="top" class="hero">
      <div>
        <p class="eyebrow">一步一提示 · 风险先确认</p>
        <h1>把复杂页面，翻译成<br /><em>看得见的下一步</em></h1>
      </div>
      <p class="hero-copy">上传当前手机截图并告诉我们目标。易屏会理解页面、标出目标位置，并在页面变化时重新规划。</p>
    </section>
    <section class="workspace" aria-label="Mock 核心闭环">
      <div class="preview-column">
        <div class="section-heading">
          <div><span>01</span><h2>当前页面</h2></div>
          <button class="text-button" type="button" :disabled="loading" @click="loadDemo">载入内置演示图</button>
        </div>
        <PhonePreview
          :image-url="imageUrl"
          :bbox="targetElement?.bbox"
          :target-label="targetElement?.text"
          :high-contrast="highContrast"
        />
        <label class="upload-card">
          <input type="file" accept="image/png,image/jpeg" @change="onFileChange" />
          <span class="upload-icon" aria-hidden="true">↑</span>
          <span><strong>选择 PNG / JPG 截图</strong><small>当前版本最大 8 MB，默认不保存</small></span>
        </label>
      </div>
      <div class="control-column">
        <div class="section-heading"><div><span>02</span><h2>任务与模式</h2></div></div>
        <label class="field-label" for="task">你想完成什么？</label>
        <textarea id="task" v-model="taskText" rows="3" maxlength="500" placeholder="例如：帮我关闭自动续费"></textarea>
        <fieldset class="mode-group">
          <legend>辅助模式</legend>
          <label v-for="item in modeOptions" :key="item.value" class="mode-card" :class="{ active: mode === item.value }">
            <input v-model="mode" type="radio" name="mode" :value="item.value" />
            <span><strong>{{ item.label }}</strong><small>{{ item.hint }}</small></span>
          </label>
        </fieldset>
        <button class="primary-button" type="button" :disabled="!ready" @click="runAnalysis">
          <span v-if="loading" class="spinner" aria-hidden="true"></span>
          {{ loading ? '正在处理…' : session?.state === 'OBSERVING' ? '继续识别当前页' : '开始识别下一步' }}
        </button>
        <p v-if="errorMessage" class="alert alert--error" role="alert">{{ errorMessage }}</p>
        <article class="guide-card" aria-live="polite">
          <div class="guide-meta">
            <span>第 {{ session?.step_number ?? 1 }} 步</span>
            <span v-if="analysis">页面置信度 {{ Math.round(analysis.page.confidence * 100) }}%</span>
            <span v-if="analysis">{{ analysis.analysis_source === 'model' ? 'AI视觉分析' : 'Mock演示' }}</span>
          </div>
          <h3>{{ analysis?.page.title ?? '等待识别' }}</h3>
          <p class="guide-instruction">{{ guidance }}</p>
          <p v-if="analysis" class="page-summary">{{ analysis.page.summary }}</p>
          <div v-if="analysis" class="risk-line" :class="`risk-line--${analysis.risk.level}`">
            <span>风险：{{ analysis.risk.level }}</span>
            <span>{{ analysis.risk.confirmation_required ? '需要确认' : '可继续引导' }}</span>
          </div>
          <div v-if="analysis?.risk.confirmation_required && session?.state === 'WAITING_CONFIRMATION'" class="risk-actions">
            <button type="button" class="secondary-button" @click="handleRisk(false)">暂停操作</button>
            <button type="button" class="warning-button" @click="handleRisk(true)">我已了解影响</button>
          </div>
          <div v-else-if="session?.state === 'WAITING_FEEDBACK'" class="feedback-grid">
            <button type="button" @click="sendFeedback('done')">✓ 我已完成</button>
            <button type="button" @click="sendFeedback('not_found')">⌕ 没找到</button>
            <button type="button" @click="sendFeedback('unexpected_page')">↻ 页面不一样</button>
            <button type="button" @click="sendFeedback('wrong_action')">↩ 我点错了</button>
          </div>
        </article>
        <div class="session-strip">
          <span>会话状态</span>
          <strong>{{ session?.state ?? '未创建' }}</strong>
          <span v-if="session">偏航 {{ session.deviation_count }} 次</span>
        </div>
      </div>
    </section>
  </main>
</template>
