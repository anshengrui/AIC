import type {
  AnalysisResponse,
  AssistanceMode,
  FeedbackResponse,
  FeedbackType,
  HealthResponse,
  SessionView,
} from '../types'

interface ApiErrorBody {
  detail?: { code?: string; message?: string } | string
}

async function parseResponse<T>(response: Response): Promise<T> {
  if (response.ok) return response.json() as Promise<T>
  let message = `请求失败（${response.status}）`
  try {
    const body = (await response.json()) as ApiErrorBody
    if (typeof body.detail === 'string') message = body.detail
    else if (body.detail?.message) message = body.detail.message
  } catch {
    // Keep stable fallback when the server returns a non-JSON response.
  }
  throw new Error(message)
}

export async function getHealth(): Promise<HealthResponse> {
  const response = await fetch('/api/health')
  return parseResponse<HealthResponse>(response)
}

export async function createSession(taskText: string, mode: AssistanceMode): Promise<SessionView> {
  const response = await fetch('/api/sessions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ task_text: taskText, mode }),
  })
  return parseResponse<SessionView>(response)
}

export async function analyzeScreenshot(sessionId: string, file: File): Promise<AnalysisResponse> {
  const form = new FormData()
  form.append('image', file)
  const response = await fetch(`/api/sessions/${sessionId}/analyze`, {
    method: 'POST',
    body: form,
  })
  return parseResponse<AnalysisResponse>(response)
}

export async function submitFeedback(
  sessionId: string,
  feedback: FeedbackType,
): Promise<FeedbackResponse> {
  const response = await fetch(`/api/sessions/${sessionId}/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ feedback }),
  })
  return parseResponse<FeedbackResponse>(response)
}

export async function confirmRisk(sessionId: string, approved: boolean): Promise<FeedbackResponse> {
  const response = await fetch(`/api/sessions/${sessionId}/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ approved }),
  })
  return parseResponse<FeedbackResponse>(response)
}
