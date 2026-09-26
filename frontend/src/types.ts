export type AssistanceMode = 'standard' | 'senior' | 'low_vision_voice'
export type FeedbackType = 'done' | 'not_found' | 'unexpected_page' | 'wrong_action' | 'cancel'
export type SessionState =
  | 'CREATED'
  | 'OBSERVING'
  | 'GUIDING'
  | 'WAITING_FEEDBACK'
  | 'WAITING_CONFIRMATION'
  | 'BLOCKED'
  | 'COMPLETED'

export interface PageInfo {
  page_type: string
  title: string
  summary: string
  confidence: number
}

export interface UIElement {
  id: string
  text: string
  role: 'button' | 'input' | 'link' | 'tab' | 'dialog' | 'text' | 'image' | 'unknown'
  bbox: [number, number, number, number]
  clickable: boolean
  goal_relevance: number
  confidence: number
  risk_hints: string[]
}

export interface AnalysisResponse {
  session_id: string
  page: PageInfo
  elements: UIElement[]
  recommended_action: {
    action_type: 'tap' | 'type' | 'scroll' | 'back' | 'wait' | 'ask_user' | 'stop'
    element_id: string | null
    instruction: string
    expected_next_state: string
    confidence: number
  }
  risk: {
    level: 'low' | 'medium' | 'high' | 'critical'
    categories: string[]
    confirmation_required: boolean
  }
  task_status: 'not_started' | 'in_progress' | 'blocked' | 'completed' | 'uncertain'
  analysis_source: 'mock' | 'model'
  model_name: string | null
}

export interface HealthResponse {
  status: string
  mock_mode: boolean
  model_configured: boolean
  save_screenshots: boolean
  analysis_mode: 'mock' | 'model'
  model_name: string | null
}

export interface SessionView {
  session_id: string
  task_text: string
  mode: AssistanceMode
  state: SessionState
  task_status: AnalysisResponse['task_status']
  step_number: number
  deviation_count: number
  latest_analysis: AnalysisResponse | null
}

export interface FeedbackResponse {
  session: SessionView
  guidance: string
  needs_new_screenshot: boolean
}
