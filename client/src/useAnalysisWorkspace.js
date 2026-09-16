import { computed, ref } from 'vue'
import { apiRequest } from './api'
import { DEMO_EVALUATION, DEMO_ITEM, DEMO_PLAN, DEMO_RESULT, DEMO_TRACE } from './demoData'
import { renderMarkdown } from './markdown'

const DEFAULT_GOAL = 'Understand the core content, extract key takeaways, and provide timestamped evidence with practical next steps.'

// GENERAL, LEARNING, REVIEW, and CREATION map directly to backend AnalysisMode values.
// AUTO is frontend-only: resolve it through /analysis/route before submitting a concrete mode.
// It is never sent to keyed backend endpoints, preventing read/write key asymmetry.
const ANALYSIS_MODES = [
  { value: 'AUTO', title: 'Auto', description: 'Let AI choose the best analysis mode' },
  { value: 'GENERAL', title: 'General', description: 'Takeaways · evidence · actions' },
  { value: 'LEARNING', title: 'Learning', description: 'Concept map · key ideas · quiz' },
  { value: 'REVIEW', title: 'Review', description: 'Gaps · claims · missing context' },
  { value: 'CREATION', title: 'Creation', description: 'Highlights · titles · script ideas' }
]
const GOAL_PRESETS = [
  {
    title: 'Study notes',
    description: 'Chapters, concepts, and review prompts',
    prompt: 'Create structured study notes by chapter, cite key timestamps, and suggest how to review the material.'
  },
  {
    title: 'Meeting notes',
    description: 'Decisions, disagreements, and action items',
    prompt: 'Create meeting notes with major topics, decisions, disagreements, action items, and supporting timestamps.'
  },
  {
    title: 'How-to guide',
    description: 'Steps, prerequisites, and edge cases',
    prompt: 'Create an actionable how-to guide with prerequisites, steps, cautions, edge cases, and supporting timestamps.'
  }
]
const STAGE_LABELS = {
  VIDEO_CONTEXT: 'Reading speech and visuals',
  RETRIEVAL: 'Finding relevant evidence',
  PLANNER: 'Planning the analysis',
  EXECUTOR: 'Producing structured results',
  CRITIC: 'Checking findings and evidence'
}

function createSidebarState() {
  return {
    visible: false,
    type: 'ai',
    mode: 'compose',
    title: '',
    content: '',
    error: '',
    loading: false,
    statusMessage: '',
    streamOffline: false,
    streamRetry: 0,
    mediaId: null,
    goal: DEFAULT_GOAL,
    analysisMode: 'GENERAL',
    playbackUrl: '',
    playbackLoading: false,
    playbackError: '',
    ingestionMode: 'FULL_VIDEO',
    preparingVisual: false,
    followUp: '',
    followUpLoading: false,
    evidenceQuery: '',
    evidenceLoading: false,
    evidenceResults: [],
    evidenceError: '',
    plan: null,
    trace: null,
    evaluation: null,
    feedback: null,
    feedbackLoading: false,
    editingPlan: false,
    planDraft: [],
    rerunLoading: false
  }
}

export function useAnalysisWorkspace({
  demoMode,
  taskStreams,
  showMessage,
  refreshMediaList,
  findMediaItem,
  onAnswerAppended = () => {}
}) {
  const sidebar = ref(createSidebarState())
  let evidenceRequestVersion = 0
  const traceStages = computed(() => Object.entries(sidebar.value.trace?.stageDurationMs || {})
    .map(([stage, duration]) => [STAGE_LABELS[stage] || stage, formatDuration(duration)]))
  const renderedMarkdown = computed(() => renderMarkdown(sidebar.value.content))
  const isCurrentWorkspace = (id, type, goal = null, analysisMode = null) => sidebar.value.mediaId === id
    && sidebar.value.type === type
    && (goal === null || sidebar.value.goal === goal)
    && (analysisMode === null || sidebar.value.analysisMode === analysisMode)

  const openSidebar = (type, title) => {
    sidebar.value.visible = true
    sidebar.value.type = type
    sidebar.value.title = title
    sidebar.value.loading = true
    sidebar.value.content = ''
    sidebar.value.error = ''
    sidebar.value.statusMessage = ''
    sidebar.value.streamOffline = false
    sidebar.value.streamRetry = 0
  }

  const closeSidebar = () => {
    if (sidebar.value.type === 'ai' && sidebar.value.mediaId) {
      // Persist goal and mode together because both identify a task and must be restored together.
      saveGoalDraft(sidebar.value.mediaId, sidebar.value.goal)
      saveModeDraft(sidebar.value.mediaId, sidebar.value.analysisMode)
    }
    evidenceRequestVersion += 1
    sidebar.value.visible = false
  }

  const loadPlayback = async id => {
    sidebar.value.playbackLoading = true
    sidebar.value.playbackError = ''
    try {
      const response = await apiRequest(`/media/playback?id=${id}`)
      const url = await response.text()
      if (!response.ok) throw new Error(url || 'Unable to load the video.')
      if (sidebar.value.mediaId === id) {
        sidebar.value.playbackUrl = url
        sidebar.value.playbackError = ''
      }
    } catch (error) {
      console.warn('Video preview unavailable', error)
      if (sidebar.value.mediaId === id) {
        sidebar.value.playbackUrl = ''
        sidebar.value.playbackError = error.message || 'The source video is unavailable right now.'
      }
    } finally {
      if (sidebar.value.mediaId === id) sidebar.value.playbackLoading = false
    }
  }

  const refreshAgentMeta = async (
    id,
    goal,
    includeEvaluation,
    analysisMode = sidebar.value.analysisMode || 'GENERAL'
  ) => {
    const params = new URLSearchParams({ id: String(id), goal, mode: analysisMode })
    const requests = [
      apiRequest(`/analysis/agent-plan?${params}`),
      apiRequest(`/analysis/agent-trace?${params}`)
    ]
    if (includeEvaluation) requests.push(apiRequest(`/analysis/agent-evaluation?${params}`))

    const settled = await Promise.allSettled(requests)
    if (!isCurrentWorkspace(id, 'ai', goal, analysisMode)) return
    const [plan, trace, evaluation] = await Promise.all(settled.map(readSettledJson))
    if (plan && !sidebar.value.editingPlan) sidebar.value.plan = plan
    if (trace) sidebar.value.trace = trace
    if (includeEvaluation && evaluation) sidebar.value.evaluation = evaluation
  }

  const startTaskStream = (id, type, goal = '', analysisMode = 'GENERAL') => {
    const resolvedMode = analysisMode || 'GENERAL'
    const scope = type === 'ai' ? analysisScope(goal, resolvedMode) : ''
    const isCurrentTask = () => isCurrentWorkspace(
      id, type, type === 'ai' ? goal : null, type === 'ai' ? resolvedMode : null)
    const taskLabel = type === 'ai' ? 'AI analysis' : 'transcription'
    const finish = async (result, failed = false) => {
      const watching = sidebar.value.visible && isCurrentTask()
      if (watching) {
        sidebar.value.content = failed ? '' : result
        sidebar.value.loading = false
        sidebar.value.statusMessage = ''
        sidebar.value.streamOffline = false
        sidebar.value.streamRetry = 0
        sidebar.value.error = failed ? result : ''
        if (failed && type === 'ai') sidebar.value.mode = 'compose'
        if (type === 'ai' && !failed) await refreshAgentMeta(id, goal, true, resolvedMode)
      }
      // Include the file name so users know which task finished after switching videos.
      const filename = watching ? '' : findMediaItem(id)?.filename || ''
      const suffix = filename ? ` · ${filename}` : ''
      showMessage(
        failed
          ? `${taskLabel} failed${suffix}: ${result || 'please try again shortly'}`
          : `${taskLabel} complete${suffix}`,
        failed
      )
      taskStreams.stop(id, type, scope)
    }

    const params = new URLSearchParams({ id: String(id) })
    if (type === 'ai') {
      params.set('goal', goal)
      params.set('mode', resolvedMode)
    }
    const path = type === 'ai'
      ? `/analysis/analysis-events?${params}`
      : `/analysis/transcription-events?${params}`

    taskStreams.start(id, type, scope, path, async status => {
      if (isCurrentTask()) {
        // Any event confirms the connection is live, so clear the reconnecting indicator.
        sidebar.value.streamOffline = false
        sidebar.value.streamRetry = 0
        if (status.message && (status.state === 'PROCESSING' || status.state === 'QUEUED')) {
          sidebar.value.statusMessage = status.message
        }
      }
      if (type === 'ai' && status.stage && isCurrentTask()) {
        await refreshAgentMeta(id, goal, false, resolvedMode)
      }
      if (status.state === 'COMPLETED') {
        await refreshMediaList()
        await finish(status.result || (type === 'ai' ? 'Analysis complete' : ''))
      } else if (status.state === 'FAILED') {
        await finish(status.message || 'Task failed', true)
      }
    }, (error, attempt, terminal = false) => {
      // Do not write stale errors after a user switches videos or closes the panel.
      if (terminal) {
        console.warn('task event stream stopped', error)
        if (!isCurrentTask()) return
        sidebar.value.streamOffline = false
        sidebar.value.streamRetry = 0
        sidebar.value.loading = false
        sidebar.value.error = error?.message || 'The task event stream disconnected. Please try again shortly.'
        return
      }
      console.warn('task event stream reconnecting', error)
      if (!isCurrentTask()) return
      sidebar.value.streamOffline = true
      sidebar.value.streamRetry = attempt || 1
    })
  }

  const transcribe = async id => {
    const item = findMediaItem(id)
    if (demoMode) {
      openSidebar('text', 'ASR transcript')
      sidebar.value.content = item?.transcriptText || DEMO_ITEM.transcriptText
      sidebar.value.loading = false
      return
    }
    const panelTitle = item?.filename ? `Full transcript · ${item.filename}` : 'Full transcript'
    if (taskStreams.has(id, 'text')) {
      openSidebar('text', panelTitle)
      sidebar.value.mediaId = id
      sidebar.value.statusMessage = 'Transcription is still running in the background. Progress will update automatically.'
      return
    }

    openSidebar('text', panelTitle)
    sidebar.value.mediaId = id
    sidebar.value.statusMessage = 'Transcription submitted. Speech recognition is starting.'
    try {
      const current = await apiRequest(`/analysis/transcription-status?id=${id}`)
      if (!current.ok) throw new Error(await current.text())
      const currentStatus = await current.json()
      if (currentStatus.state === 'COMPLETED') {
        if (isCurrentWorkspace(id, 'text')) {
          sidebar.value.content = currentStatus.result || ''
          sidebar.value.statusMessage = ''
          sidebar.value.loading = false
        }
        return
      }
      if (currentStatus.state === 'QUEUED' || currentStatus.state === 'PROCESSING') {
        if (isCurrentWorkspace(id, 'text') && currentStatus.message) {
          sidebar.value.statusMessage = currentStatus.message
        }
        startTaskStream(id, 'text')
        return
      }
      const response = await apiRequest(`/analysis/transcribe?id=${id}`, { method: 'POST' })
      if (!response.ok) throw new Error(await response.text())
      startTaskStream(id, 'text')
    } catch (error) {
      if (isCurrentWorkspace(id, 'text')) {
        sidebar.value.content = ''
        sidebar.value.statusMessage = ''
        sidebar.value.error = error.message || 'Transcription failed. Please try again shortly.'
        sidebar.value.loading = false
      }
    }
  }

  const analyze = async (id, goal, mode = 'GENERAL') => {
    const resolvedMode = mode || 'GENERAL'
    const scope = analysisScope(goal, resolvedMode)
    if (taskStreams.has(id, 'ai', scope)) {
      sidebar.value.mode = 'result'
      sidebar.value.loading = true
      sidebar.value.statusMessage = 'An analysis for this goal is already running. Taking over its progress.'
      return
    }

    sidebar.value.loading = true
    sidebar.value.mode = 'result'
    sidebar.value.content = ''
    sidebar.value.statusMessage = 'Analysis submitted and waiting to enter the Agent pipeline.'
    sidebar.value.streamOffline = false
    sidebar.value.streamRetry = 0
    try {
      const params = new URLSearchParams({ id: String(id), goal, mode: resolvedMode })
      const response = await apiRequest(`/analysis/ai?${params}`, { method: 'POST' })
      const message = await response.text()
      if (response.status === 409) {
        startTaskStream(id, 'ai', goal, resolvedMode)
        refreshAgentMeta(id, goal, false, resolvedMode)
        return
      }
      if (!response.ok) {
        if (isCurrentWorkspace(id, 'ai', goal, resolvedMode)) {
          showMessage(message, true)
          sidebar.value.loading = false
          sidebar.value.statusMessage = ''
          sidebar.value.mode = 'compose'
          sidebar.value.error = message
        }
        return
      }
      startTaskStream(id, 'ai', goal, resolvedMode)
      refreshAgentMeta(id, goal, false, resolvedMode)
    } catch (error) {
      if (isCurrentWorkspace(id, 'ai', goal, resolvedMode)) {
        sidebar.value.mode = 'compose'
        sidebar.value.error = error.message || String(error)
        sidebar.value.loading = false
        sidebar.value.statusMessage = ''
      }
    }
  }

  const openAgent = async item => {
    evidenceRequestVersion += 1
    const goal = loadGoalDraft(item.id)
    // Restore the previous mode, which combines with the goal to identify a task; default to general.
    const analysisMode = loadModeDraft(item.id)
    sidebar.value = {
      ...createSidebarState(),
      visible: true,
      title: `Video Agent · ${item.filename}`,
      mediaId: item.id,
      goal,
      analysisMode,
      ingestionMode: item.ingestionMode || 'FULL_VIDEO'
    }
    if (demoMode) return

    loadPlayback(item.id)
    try {
      const params = new URLSearchParams({ id: String(item.id), goal, mode: sidebar.value.analysisMode || 'GENERAL' })
      const response = await apiRequest(`/analysis/analysis-status?${params}`)
      if (!response.ok) {
        const detail = await response.text()
        throw new Error(detail || 'Unable to load the previous analysis status.')
      }
      const status = await response.json()
      if (sidebar.value.mediaId !== item.id
        || sidebar.value.goal !== goal
        || sidebar.value.analysisMode !== analysisMode) return

      if (status.state === 'COMPLETED') {
        sidebar.value.mode = 'result'
        sidebar.value.content = status.result || ''
        sidebar.value.loading = false
        await refreshAgentMeta(item.id, goal, true, analysisMode)
      } else if (status.state === 'QUEUED' || status.state === 'PROCESSING') {
        sidebar.value.mode = 'result'
        sidebar.value.loading = true
        sidebar.value.statusMessage = status.message || 'Restoring the previous unfinished analysis.'
        startTaskStream(item.id, 'ai', goal, analysisMode)
        await refreshAgentMeta(item.id, goal, false, analysisMode)
      } else if (status.state === 'FAILED') {
        sidebar.value.error = status.message || 'The previous analysis did not finish. You can submit it again.'
      }
    } catch (error) {
      console.warn('Previous analysis unavailable', error)
      if (sidebar.value.mediaId === item.id
        && sidebar.value.goal === goal
        && sidebar.value.analysisMode === analysisMode) {
        sidebar.value.error = error.message || 'Unable to load the previous analysis status. You can submit again.'
      }
    }
  }

  const showDemoResult = () => {
    sidebar.value.mode = 'result'
    sidebar.value.loading = false
    sidebar.value.content = DEMO_RESULT
    if (!sidebar.value.plan) sidebar.value.plan = DEMO_PLAN
    sidebar.value.trace = DEMO_TRACE
    sidebar.value.evaluation = DEMO_EVALUATION
  }

  // Resolve AUTO from the goal text. apiRequest unwraps the envelope, so the response is { mode, reason }.
  const routeMode = async goal => {
    const response = await apiRequest('/analysis/route', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ goal })
    })
    if (!response.ok) throw new Error(await response.text())
    return response.json()
  }

  const submitAgent = async () => {
    const goal = sidebar.value.goal.trim()
    if (!goal || sidebar.value.loading) return
    const mediaId = sidebar.value.mediaId
    // Backend requests, SSE scope, and callbacks must use the same normalized goal to avoid key mismatches.
    sidebar.value.goal = goal
    sidebar.value.error = ''
    saveGoalDraft(mediaId, goal)
    if (demoMode) {
      sidebar.value.mode = 'result'
      sidebar.value.loading = true
      sidebar.value.plan = DEMO_PLAN
      sidebar.value.trace = DEMO_TRACE
      setTimeout(showDemoResult, 450)
      return
    }
    let mode = sidebar.value.analysisMode || 'GENERAL'
    if (mode === 'AUTO') {
      // Resolve AUTO before submitting and retain the concrete mode for status, retry, and metadata calls.
      sidebar.value.mode = 'result'
      sidebar.value.loading = true
      sidebar.value.content = ''
      sidebar.value.statusMessage = 'Identifying the best analysis mode…'
      let decision = null
      try {
        decision = await routeMode(goal)
      } catch (error) {
        console.warn('intent routing failed', error)
      }
      // Routing is asynchronous; apply its result only while the user remains on the same task.
      if (sidebar.value.mediaId !== mediaId || sidebar.value.goal.trim() !== goal) return
      if (decision && decision.mode) {
        mode = decision.mode
        showMessage(`AI selected ${modeTitle(mode)} mode: ${decision.reason || ''}`.trim())
      } else {
        mode = 'GENERAL'
        showMessage('Mode detection is unavailable; using General mode instead.', true)
      }
      sidebar.value.analysisMode = mode
    }
    // Remember the concrete mode so reopening the panel retrieves this analysis result.
    saveModeDraft(mediaId, mode)
    analyze(mediaId, goal, mode)
  }

  const startNewAnalysis = () => {
    sidebar.value.mode = 'compose'
    sidebar.value.loading = false
    sidebar.value.error = ''
  }

  const startPlanEdit = () => {
    sidebar.value.planDraft = [...(sidebar.value.plan?.tasks || [])]
    sidebar.value.editingPlan = true
  }

  const cancelPlanEdit = () => {
    sidebar.value.editingPlan = false
    sidebar.value.planDraft = []
  }

  const addPlanTask = () => {
    if (sidebar.value.planDraft.length < 5) sidebar.value.planDraft.push('')
  }

  const removePlanTask = index => {
    if (sidebar.value.planDraft.length > 1) sidebar.value.planDraft.splice(index, 1)
  }

  const rerunWithPlan = async () => {
    const tasks = sidebar.value.planDraft.map(task => task.trim()).filter(Boolean)
    if (!tasks.length || tasks.length > 5) {
      showMessage('Keep between 1 and 5 valid tasks in the plan.', true)
      return
    }
    if (demoMode) {
      sidebar.value.plan = { ...DEMO_PLAN, tasks }
      cancelPlanEdit()
      sidebar.value.loading = true
      setTimeout(showDemoResult, 450)
      return
    }

    const mediaId = sidebar.value.mediaId
    const goal = sidebar.value.goal
    const analysisMode = sidebar.value.analysisMode || 'GENERAL'
    sidebar.value.rerunLoading = true
    try {
      const reviseParams = new URLSearchParams({ mode: analysisMode })
      const response = await apiRequest(`/analysis/agent-revise?${reviseParams}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          mediaId,
          goal,
          correctedTasks: tasks,
          comment: 'Re-run after the user edited the Planner tasks'
        })
      })
      const message = await response.text()
      if (!response.ok) throw new Error(message || 'Unable to resubmit the analysis.')
      if (isCurrentWorkspace(mediaId, 'ai', goal, analysisMode)) {
        sidebar.value.plan = { ...sidebar.value.plan, tasks }
        cancelPlanEdit()
        sidebar.value.content = ''
        sidebar.value.loading = true
        sidebar.value.statusMessage = 'Resubmitted with the new plan. Running again now.'
        sidebar.value.streamOffline = false
        sidebar.value.streamRetry = 0
      }
      startTaskStream(mediaId, 'ai', goal, analysisMode)
    } catch (error) {
      if (isCurrentWorkspace(mediaId, 'ai', goal, analysisMode)) {
        showMessage(error.message || 'Unable to resubmit the analysis.', true)
      }
    } finally {
      if (isCurrentWorkspace(mediaId, 'ai', goal, analysisMode)) sidebar.value.rerunLoading = false
    }
  }

  const submitFollowUp = async () => {
    const question = sidebar.value.followUp.trim()
    if (!question || sidebar.value.followUpLoading) return
    if (demoMode) {
      sidebar.value.content += `\n\n## Follow-up\n${question}\n\nAt 08:42, the lesson explains that the iterative version stores pending nodes in an explicit stack. Time complexity remains O(n), with O(h) additional space.`
      sidebar.value.followUp = ''
      onAnswerAppended()
      return
    }

    const mediaId = sidebar.value.mediaId
    const goal = sidebar.value.goal
    const analysisMode = sidebar.value.analysisMode || 'GENERAL'
    sidebar.value.followUpLoading = true
    try {
      const params = new URLSearchParams({
        id: String(mediaId),
        question,
        goal,
        mode: analysisMode
      })
      const response = await apiRequest(`/analysis/follow-up?${params}`, { method: 'POST' })
      const answer = await response.text()
      if (!response.ok) throw new Error(answer || 'Unable to answer the follow-up.')
      if (isCurrentWorkspace(mediaId, 'ai', goal, analysisMode)) {
        sidebar.value.content += `\n\n## Follow-up\n${question}\n\n${answer}`
        sidebar.value.followUp = ''
        // Scroll to appended answers so users can see the action completed.
        onAnswerAppended()
      }
    } catch (error) {
      if (isCurrentWorkspace(mediaId, 'ai', goal, analysisMode)) {
        showMessage(`❌ ${error.message}`, true)
      }
    } finally {
      if (isCurrentWorkspace(mediaId, 'ai', goal, analysisMode)) sidebar.value.followUpLoading = false
    }
  }

  const searchEvidence = async () => {
    const query = sidebar.value.evidenceQuery.trim()
    if (!query || sidebar.value.evidenceLoading) return
    const requestVersion = ++evidenceRequestVersion
    const mediaId = sidebar.value.mediaId
    sidebar.value.evidenceLoading = true
    sidebar.value.evidenceError = ''
    sidebar.value.evidenceResults = []
    try {
      if (demoMode) {
        const demoResults = [{
          startMs: 522000,
          endMs: 582000,
          source: 'ASR+OCR',
          snippet: 'Iterative traversal stores pending nodes in an explicit stack; the screen shows the preorder sequence.',
          transcript: 'Iterative traversal stores pending nodes in an explicit stack.',
          ocrTexts: ['Preorder: root, left subtree, right subtree']
        }]
        if (requestVersion === evidenceRequestVersion) {
          sidebar.value.evidenceResults = demoResults
        }
        return
      }
      const params = new URLSearchParams({
        id: String(mediaId),
        query
      })
      const response = await apiRequest(`/analysis/evidence-search?${params}`)
      if (!response.ok) {
        const detail = await response.text()
        throw new Error(detail || 'Unable to search video evidence.')
      }
      const results = await response.json()
      if (requestVersion !== evidenceRequestVersion || sidebar.value.mediaId !== mediaId) return
      sidebar.value.evidenceResults = Array.isArray(results) ? results : []
      if (!sidebar.value.evidenceResults.length) {
        sidebar.value.evidenceError = 'No matching video evidence was found.'
      }
    } catch (error) {
      if (requestVersion !== evidenceRequestVersion || sidebar.value.mediaId !== mediaId) return
      sidebar.value.evidenceResults = []
      sidebar.value.evidenceError = error.message || 'Unable to search video evidence.'
    } finally {
      if (requestVersion === evidenceRequestVersion && sidebar.value.mediaId === mediaId) {
        sidebar.value.evidenceLoading = false
      }
    }
  }

  const sendFeedback = async rating => {
    if (sidebar.value.feedbackLoading || sidebar.value.feedback === rating) return
    if (demoMode) {
      sidebar.value.feedback = rating
      showMessage('Demo feedback recorded.')
      return
    }
    const mediaId = sidebar.value.mediaId
    const goal = sidebar.value.goal
    const analysisMode = sidebar.value.analysisMode || 'GENERAL'
    sidebar.value.feedbackLoading = true
    try {
      const response = await apiRequest('/analysis/agent-feedback', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mediaId, goal, mode: analysisMode, rating })
      })
      if (!response.ok) throw new Error(await response.text())
      if (isCurrentWorkspace(mediaId, 'ai', goal, analysisMode)) {
        sidebar.value.feedback = rating
        showMessage('Feedback recorded.')
      }
    } catch (error) {
      if (isCurrentWorkspace(mediaId, 'ai', goal, analysisMode)) {
        showMessage(`❌ ${error.message}`, true)
      }
    } finally {
      if (isCurrentWorkspace(mediaId, 'ai', goal, analysisMode)) sidebar.value.feedbackLoading = false
    }
  }

  const retryPlayback = () => {
    if (sidebar.value.mediaId && !sidebar.value.playbackLoading) {
      loadPlayback(sidebar.value.mediaId)
    }
  }

  const prepareVisualAnalysis = async () => {
    const mediaId = sidebar.value.mediaId
    if (!mediaId || sidebar.value.preparingVisual) return
    sidebar.value.preparingVisual = true
    sidebar.value.playbackError = 'Downloading the full source for visual evidence. This can take a few minutes.'
    try {
      const response = await apiRequest(`/media/prepare-full-video?id=${mediaId}`, { method: 'POST' })
      if (!response.ok) throw new Error(await response.text())
      const media = await response.json()
      if (sidebar.value.mediaId !== mediaId) return
      sidebar.value.ingestionMode = media.ingestionMode || 'FULL_VIDEO'
      sidebar.value.playbackError = ''
      await refreshMediaList()
      loadPlayback(mediaId)
      showMessage('Full video is ready for visual analysis.')
    } catch (error) {
      if (sidebar.value.mediaId === mediaId) {
        sidebar.value.playbackError = error.message || 'Unable to prepare the full video.'
      }
    } finally {
      if (sidebar.value.mediaId === mediaId) sidebar.value.preparingVisual = false
    }
  }

  const handlePlaybackError = () => {
    if (!sidebar.value.playbackUrl) return
    sidebar.value.playbackUrl = ''
    sidebar.value.playbackError = 'This video cannot be played. Its source may be unavailable or its codec may not be supported by this browser. Try reloading or importing it again.'
  }

  const resetWorkspace = () => {
    evidenceRequestVersion += 1
    sidebar.value = createSidebarState()
  }

  const discardMediaWorkspace = mediaId => {
    taskStreams.stopMedia(mediaId)
    try {
      localStorage.removeItem(goalDraftKey(mediaId))
      localStorage.removeItem(modeDraftKey(mediaId))
    } catch {
      // Storage being unavailable should not block media deletion.
    }
    if (sidebar.value.mediaId === mediaId) resetWorkspace()
  }

  return {
    sidebar,
    goalPresets: GOAL_PRESETS,
    analysisModes: ANALYSIS_MODES,
    traceStages,
    renderedMarkdown,
    transcribe,
    closeSidebar,
    openAgent,
    submitAgent,
    startNewAnalysis,
    showDemoResult,
    startPlanEdit,
    cancelPlanEdit,
    addPlanTask,
    removePlanTask,
    rerunWithPlan,
    submitFollowUp,
    searchEvidence,
    sendFeedback,
    retryPlayback,
    prepareVisualAnalysis,
    handlePlaybackError,
    resetWorkspace,
    discardMediaWorkspace,
    formatPercent: value => `${Math.round((Number(value) || 0) * 100)}%`
  }
}

async function readSettledJson(result) {
  if (result.status !== 'fulfilled' || !result.value.ok) return null
  try {
    return await result.value.json()
  } catch (error) {
    console.warn('Agent metadata response is invalid', error)
    return null
  }
}

function modeTitle(value) {
  return ANALYSIS_MODES.find(m => m.value === value)?.title || value
}

function analysisScope(goal, mode) {
  return `${mode || 'GENERAL'}:${goal}`
}

function formatDuration(value) {
  const milliseconds = Number(value) || 0
  if (milliseconds < 1000) return `${Math.round(milliseconds)} ms`
  return `${(milliseconds / 1000).toFixed(milliseconds < 10_000 ? 1 : 0)} s`
}

function goalDraftKey(mediaId) {
  return `videomind:goal:${mediaId}`
}

function loadGoalDraft(mediaId) {
  try {
    return localStorage.getItem(goalDraftKey(mediaId)) || DEFAULT_GOAL
  } catch {
    return DEFAULT_GOAL
  }
}

function saveGoalDraft(mediaId, goal) {
  if (!mediaId || !goal?.trim()) return
  try {
    localStorage.setItem(goalDraftKey(mediaId), goal.trim())
  } catch {
    // Private browsing can disable storage; the current session still works.
  }
}

function modeDraftKey(mediaId) {
  return `videomind:mode:${mediaId}`
}

function loadModeDraft(mediaId) {
  try {
    return localStorage.getItem(modeDraftKey(mediaId)) || 'GENERAL'
  } catch {
    return 'GENERAL'
  }
}

// Persist concrete modes only. AUTO is temporary; storing it would query the wrong backend state later.
function saveModeDraft(mediaId, mode) {
  if (!mediaId || !mode || mode === 'AUTO') return
  try {
    localStorage.setItem(modeDraftKey(mediaId), mode)
  } catch {
    // Private browsing can disable storage; the current session still works.
  }
}
