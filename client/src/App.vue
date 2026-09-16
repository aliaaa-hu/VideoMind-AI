<template>
  <div class="app-stage">
    <div class="ambient-noise"></div>
    <div class="ambient-glow"></div>

    <header class="navbar">
      <div class="nav-content">
        <div class="brand">
          <span class="brand-do">VideoMind-AI</span>
          <span class="beta-badge">PRO</span>
        </div>

        <div class="nav-controls">
          <button v-if="!currentUser" class="auth-btn" @click="openAuthModal">
            <span class="btn-icon">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>
            </span>
            Sign in
          </button>

          <div v-else class="user-profile">
            <span class="user-name">:: {{ currentUser.nickname }} ::</span>
            <button class="logout-btn" @click="logout" title="Sign out" aria-label="Sign out">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path><polyline points="16 17 21 12 16 7"></polyline><line x1="21" y1="12" x2="9" y2="12"></line></svg>
            </button>
          </div>

          <div class="status-pill" :class="{ 'is-active': uploading }" role="status" aria-live="polite">
            <div class="status-dot"></div>
            <span class="status-text">{{ systemStatusText }}</span>
          </div>
        </div>
      </div>
    </header>

    <main class="main-container">
      <section class="hero-section">
        <p class="hero-eyebrow"><span></span> VIDEO INTELLIGENCE WORKSPACE</p>
        <h1 class="slogan-main">Turn long videos into <em>clear next steps.</em></h1>
        <p class="slogan-sub">Upload a video or paste a link. VideoMind-AI connects speech, visuals, and time into evidence-backed understanding.</p>
        <div class="hero-signals" aria-label="Capabilities">
          <span>Multimodal analysis</span><span>Traceable evidence</span><span>Async processing</span>
        </div>

        <div class="upload-wrapper">
          <input
              type="file"
              id="file-input"
              @change="handleFileChange"
              accept="video/*"
              hidden
          />

          <div
              class="upload-magnet"
              :class="{ 'processing': uploading, 'is-dragover': isDragOver }"
              @dragenter.prevent="handleDragEnter"
              @dragover.prevent="isDragOver = true"
              @dragleave.prevent="handleDragLeave"
              @drop.prevent="handleDrop"
          >
            <div class="split-container" v-if="!uploading">

              <label for="file-input" class="skew-pane pane-local">
                <div class="pane-content unskew">
                  <div class="magnet-icon">
                    <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" y1="3" x2="12" y2="15"></line></svg>
                  </div>
                  <span class="magnet-title">LOCAL FILE</span>
                  <span class="magnet-desc">{{ isDragOver ? 'Drop to upload' : 'Click or drag a local video here' }}</span>
                </div>
              </label>

              <div class="split-gap"></div>

              <div class="skew-pane pane-url">
                <div class="pane-content unskew">
                  <div class="magnet-icon">
                    <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="2" y1="12" x2="22" y2="12"></line><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1 4-10z"></path></svg>
                  </div>
                  <span class="magnet-title">WEB LINK</span>
                  <span class="magnet-desc">YouTube, Vimeo, and more</span>

                  <div class="url-input-box" @click.stop>
                    <input
                        v-model="videoUrl"
                        type="text"
                        inputmode="url"
                        autocomplete="off"
                        spellcheck="false"
                        placeholder="Paste a video link..."
                        aria-label="Video link"
                        :disabled="uploading"
                        @keyup.enter="handleUrlUpload"
                    />
                    <button class="url-go-btn" :disabled="uploading" @click="handleUrlUpload" aria-label="Import video link">
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"></polyline></svg>
                    </button>
                  </div>
                </div>
              </div>

            </div>

            <div class="magnet-content busy" v-else>
              <div class="quantum-loader"></div>
              <span class="busy-text">{{ uploadProgress.label }}</span>
              <span v-if="uploadProgress.filename" class="busy-file">{{ uploadProgress.filename }}</span>
              <div
                  v-if="uploadProgress.percent !== null || uploadProgress.indeterminate"
                  class="upload-progress"
                  :class="{ indeterminate: uploadProgress.indeterminate }"
                  role="progressbar"
                  aria-label="Video upload progress"
                  aria-valuemin="0"
                  aria-valuemax="100"
                  :aria-valuenow="uploadProgress.percent === null ? null : uploadProgress.percent"
              >
                <span :style="uploadProgress.percent === null ? undefined : { width: `${uploadProgress.percent}%` }"></span>
              </div>
              <span v-if="uploadProgress.detail" class="busy-stat" aria-live="polite">{{ uploadProgress.detail }}</span>
              <span v-if="uploadProgress.warning" class="busy-warning" role="status">{{ uploadProgress.warning }}</span>
              <div v-if="uploadAbort" class="busy-actions">
                <button type="button" @click="cancelUpload">{{ cancelUploadLabel }}</button>
              </div>
            </div>

            <div class="border-glow"></div>
          </div>

          <div v-if="resumableFile && !uploading" class="upload-resume" role="status">
            <span>{{ resumeHint }}</span>
            <button type="button" @click="resumeUpload">Resume upload</button>
            <button type="button" @click="discardResumableUpload">Start over</button>
          </div>
        </div>
        <transition name="toast-pop">
          <div
              v-if="message"
              class="notification-bar"
              :class="{ 'error': messageIsError }"
              :role="messageIsError ? 'alert' : 'status'"
              :aria-live="messageIsError ? 'assertive' : 'polite'"
              :title="messageIsError ? 'Click to dismiss this message' : null"
              @click="dismissMessage"
          >
            {{ message }}
          </div>
        </transition>
      </section>

      <section v-if="list.length > 0" class="workspace-section">
        <div class="section-header">
          <div class="library-title">
            <h3>Video library</h3>
            <div class="count-chip">{{ list.length }} videos</div>
          </div>
          <label class="library-search">
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
            <input v-model="searchQuery" type="search" placeholder="Search your videos" aria-label="Search your videos" />
          </label>
        </div>
        <div class="card-grid">
          <div v-for="item in visibleList" :key="item.id" class="project-card">

            <button
                class="delete-btn"
                :disabled="deletingId === item.id"
                :title="deletingId === item.id ? 'Deleting…' : 'Delete video'"
                :aria-label="`Delete ${item.filename}`"
                @click.stop="deleteItem(item)"
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="3 6 5 6 21 6"></polyline><path d="M19 6l-1 14H6L5 6"></path><path d="M8 6V4h8v2"></path>
              </svg>
            </button>
            <div class="card-meta">
              <div class="meta-icon">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polygon points="23 7 16 12 23 17 23 7"></polygon><rect x="1" y="5" width="15" height="14" rx="2" ry="2"></rect></svg>
              </div>
              <div class="meta-info">
                <div class="filename-mask" :title="item.filename">{{ item.filename }}</div>
                <div class="meta-tags">
                  <span class="time-tag">{{ formatTime(item.uploadTime) }}</span>
                  <span
                      class="status-indicator"
                      :class="cardStatusClass(item)"
                      :title="cardStatusTitle(item)"
                  >
                    {{ cardStatusLabel(item) }}
                  </span>
                </div>
              </div>
            </div>

            <div class="action-dock">
              <button
                  class="dock-item"
                  :disabled="item.status !== 'COMPLETED'"
                  :title="actionTitle(item, 'Download audio')"
                  @click="downloadAudio(item)"
              >
                <span class="item-icon">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg>
                </span>
                <span class="item-label">Audio</span>
              </button>

              <button
                  class="dock-item"
                  :disabled="item.status !== 'COMPLETED'"
                  :title="actionTitle(item, 'Extract transcript')"
                  @click="transcribe(item.id)"
              >
                <span class="item-icon">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>
                </span>
                <span class="item-label">Transcript</span>
              </button>

              <button
                  class="dock-item ai-core"
                  :disabled="item.status !== 'COMPLETED'"
                  :title="actionTitle(item, 'Open VideoMind Agent')"
                  @click="openAgent(item)"
              >
                <span class="item-icon">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="4" width="16" height="16" rx="2" ry="2"></rect><rect x="9" y="9" width="6" height="6"></rect><line x1="9" y1="1" x2="9" y2="4"></line><line x1="15" y1="1" x2="15" y2="4"></line><line x1="9" y1="20" x2="9" y2="23"></line><line x1="15" y1="20" x2="15" y2="23"></line><line x1="20" y1="9" x2="23" y2="9"></line><line x1="20" y1="14" x2="23" y2="14"></line><line x1="1" y1="9" x2="4" y2="9"></line><line x1="1" y1="14" x2="4" y2="14"></line></svg>
                </span>
                <div class="label-group">
                  <span class="item-label">VideoMind Agent</span>
                </div>
                <div class="shimmer"></div>
              </button>
            </div>
          </div>
        </div>
        <div v-if="visibleList.length === 0" class="library-empty">
          <p>No videos match “{{ searchQuery }}”</p>
          <button type="button" @click="searchQuery = ''">Clear search</button>
        </div>
      </section>

      <div class="sidebar-backdrop" v-if="sidebar.visible" @click="closeSidebar"></div>
      <div
          ref="sidebarPanel"
          class="sidebar-panel"
          :class="{ 'is-open': sidebar.visible }"
          :inert="!sidebar.visible"
          role="dialog"
          aria-modal="true"
          tabindex="-1"
          :aria-label="sidebar.title || 'Task details'"
      >
        <div class="sidebar-header">
          <div class="sidebar-title">
            <span class="icon" v-if="sidebar.type === 'ai'">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12h2"></path><path d="M20 12h2"></path><path d="M12 2v2"></path><path d="M12 20v2"></path><path d="M20.2 6.47l-1.4 1.4"></path><path d="M15.9 5.35l-1.4-1.4"></path><path d="M9 11a3 3 0 1 0 6 0a3 3 0 0 0-6 0"></path></svg>
            </span>
            <span class="icon" v-else>
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>
            </span>
            {{ sidebar.title }}
          </div>
          <button class="close-btn" @click="closeSidebar" aria-label="Close analysis panel">×</button>
        </div>
        <div ref="sidebarBody" class="sidebar-body">
          <div v-if="sidebar.type === 'ai'" class="video-evidence">
            <video
                v-if="sidebar.playbackUrl"
                ref="videoPlayer"
                :src="sidebar.playbackUrl"
                controls
                playsinline
                preload="metadata"
                @error="handlePlaybackError"
            ></video>
            <div v-else-if="sidebar.playbackLoading" class="video-evidence-loading">Loading source video…</div>
            <div v-else-if="sidebar.playbackError" class="video-evidence-error" role="alert">
              <span>{{ sidebar.playbackError }}</span>
              <button v-if="sidebar.ingestionMode === 'SUBTITLE_FIRST'" type="button" :disabled="sidebar.preparingVisual" @click="prepareVisualAnalysis">
                {{ sidebar.preparingVisual ? 'Preparing video…' : 'Prepare visual analysis' }}
              </button>
              <button v-else type="button" @click="retryPlayback">Try again</button>
            </div>
            <p v-if="sidebar.playbackUrl">Select a timestamp in the analysis to jump to that moment.</p>
          </div>
          <div v-if="sidebar.type === 'ai' && sidebar.mode === 'compose'" class="agent-composer">
            <p class="agent-caption">Choose an analysis mode</p>
            <div class="goal-presets agent-mode-row">
              <button
                  v-for="m in analysisModes"
                  :key="m.value"
                  :class="{ active: sidebar.analysisMode === m.value }"
                  @click="sidebar.analysisMode = m.value"
              >
                <strong>{{ m.title }}</strong>
                <span>{{ m.description }}</span>
              </button>
            </div>
            <p class="agent-caption">Tell the Agent what you want to learn from this video.</p>
            <p v-if="sidebar.error" class="inline-error" role="alert">{{ sidebar.error }}</p>
            <textarea
                v-model="sidebar.goal"
                maxlength="500"
                placeholder="For example: summarize key ideas with timestamped evidence and practical next steps. (Ctrl / ⌘ + Enter to submit)"
                @keydown.ctrl.enter.prevent="submitAgent"
                @keydown.meta.enter.prevent="submitAgent"
            ></textarea>
            <p v-if="sidebar.goal.length > 400" class="field-counter">{{ sidebar.goal.length }} / 500 characters</p>
            <div class="goal-presets">
              <button
                  v-for="preset in goalPresets"
                  :key="preset.title"
                  :class="{ active: sidebar.goal === preset.prompt }"
                  @click="sidebar.goal = preset.prompt"
              >
                <strong>{{ preset.title }}</strong>
                <span>{{ preset.description }}</span>
              </button>
            </div>
            <button class="agent-run-btn" :disabled="!sidebar.goal.trim()" @click="submitAgent">
              {{ sidebar.error ? 'Analyze again' : 'Start analysis' }}
            </button>
          </div>

          <div v-else-if="sidebar.loading" class="agent-running">
            <div class="loading-state">
              <div class="quantum-loader small"></div>
              <p aria-live="polite">{{ loadingHeadline }}</p>
              <p v-if="sidebar.streamOffline" class="stream-offline" role="status">
                Connection interrupted. Reconnecting (attempt {{ sidebar.streamRetry }}) — your task is still running on the server.
              </p>
              <p class="loading-hint">You can close this panel. The task will continue in the background.</p>
            </div>
            <div v-if="sidebar.plan?.tasks?.length" class="agent-meta-block">
              <span class="meta-label">Task plan</span>
              <ol><li v-for="task in sidebar.plan.tasks" :key="task">{{ task }}</li></ol>
            </div>
            <div v-if="traceStages.length" class="agent-meta-block">
              <span class="meta-label">Completed stages</span>
              <div class="stage-list"><span v-for="stage in traceStages" :key="stage[0]">{{ stage[0] }} · {{ stage[1] }}</span></div>
            </div>
          </div>

          <div v-else>
            <div v-if="sidebar.type === 'ai'">
              <div class="result-actions">
                <button type="button" @click="startNewAnalysis">New analysis</button>
                <button type="button" :disabled="!sidebar.content" @click="copyResult">Copy result</button>
                <button type="button" :disabled="!sidebar.content" @click="downloadResult">Export Markdown</button>
              </div>
              <div class="evidence-search">
                <div class="evidence-search-form">
                  <input
                      v-model="sidebar.evidenceQuery"
                      aria-label="Video evidence search"
                      maxlength="500"
                      placeholder="Find a slide, caption, code sample, or explanation"
                      @keyup.enter="searchEvidence"
                  />
                  <button type="button" :disabled="sidebar.evidenceLoading || !sidebar.evidenceQuery.trim()" @click="searchEvidence">
                    {{ sidebar.evidenceLoading ? 'Searching' : 'Find evidence' }}
                  </button>
                </div>
                <p v-if="sidebar.evidenceError" class="evidence-search-error" aria-live="polite">{{ sidebar.evidenceError }}</p>
                <div v-if="sidebar.evidenceResults.length" class="evidence-search-results" aria-live="polite">
                  <button
                      v-for="hit in sidebar.evidenceResults"
                      :key="`${hit.startMs}-${hit.endMs}`"
                      type="button"
                      :title="hit.snippet || 'No text is available for this moment'"
                      @click="seekToEvidence(hit.startMs)"
                  >
                    <strong>{{ formatEvidenceTime(hit.startMs) }}</strong>
                    <small>{{ hit.source || 'Video evidence' }}</small>
                    <span>{{ hit.snippet || 'No text is available for this moment' }}</span>
                  </button>
                </div>
              </div>
              <div class="markdown-content" v-html="renderedMarkdown" @click="seekEvidence"></div>
              <details v-if="sidebar.plan?.tasks?.length || traceStages.length" class="agent-inspector">
                <summary>Analysis details</summary>
                <div class="agent-inspector-content">
                <div v-if="sidebar.plan?.tasks?.length" class="agent-meta-block">
                  <span class="meta-label">Planner tasks</span>
                  <div v-if="sidebar.editingPlan" class="plan-editor">
                    <div v-for="(_, index) in sidebar.planDraft" :key="index" class="plan-editor-row">
                      <input v-model="sidebar.planDraft[index]" maxlength="500" :aria-label="`Task ${index + 1}`" />
                      <button type="button" title="Remove task" @click="removePlanTask(index)">×</button>
                    </div>
                    <button v-if="sidebar.planDraft.length < 5" type="button" @click="addPlanTask">Add task</button>
                    <div class="plan-editor-actions">
                      <button type="button" @click="cancelPlanEdit">Cancel</button>
                      <button type="button" :disabled="sidebar.rerunLoading" @click="rerunWithPlan">
                        {{ sidebar.rerunLoading ? 'Submitting' : 'Run with this plan' }}
                      </button>
                    </div>
                  </div>
                  <template v-else>
                    <ol><li v-for="task in sidebar.plan.tasks" :key="task">{{ task }}</li></ol>
                    <button type="button" class="plan-edit-trigger" @click="startPlanEdit">Edit plan</button>
                  </template>
                </div>
                <div v-if="traceStages.length" class="agent-meta-block">
                  <span class="meta-label">Execution trace</span>
                  <div class="stage-list"><span v-for="stage in traceStages" :key="stage[0]">{{ stage[0] }} · {{ stage[1] }}</span></div>
                </div>
                <div v-if="sidebar.evaluation && Object.keys(sidebar.evaluation).length" class="quality-row">
                  <span>Structure {{ sidebar.evaluation.structuredValid ? 'valid' : 'needs work' }}</span>
                  <span>Evidence support {{ formatPercent(sidebar.evaluation.evidenceSupportRate) }}</span>
                  <span>Critic {{ sidebar.evaluation.criticPassed ? 'passed' : 'round limit reached' }}</span>
                </div>
                </div>
              </details>
              <div class="follow-up-box">
                <textarea
                    v-model="sidebar.followUp"
                    maxlength="500"
                    placeholder="Ask a follow-up about this video… (Ctrl / ⌘ + Enter to send)"
                    @keydown.ctrl.enter.prevent="submitFollowUp"
                    @keydown.meta.enter.prevent="submitFollowUp"
                ></textarea>
                <button :disabled="sidebar.followUpLoading || !sidebar.followUp.trim()" @click="submitFollowUp">
                  {{ sidebar.followUpLoading ? 'Analyzing' : 'Ask' }}
                </button>
              </div>
              <div class="feedback-row">
                <span>Was this helpful?</span>
                <button :disabled="sidebar.feedbackLoading" :class="{ active: sidebar.feedback === 1 }" :aria-pressed="sidebar.feedback === 1" @click="sendFeedback(1)" title="Helpful">Helpful</button>
                <button :disabled="sidebar.feedbackLoading" :class="{ active: sidebar.feedback === -1 }" :aria-pressed="sidebar.feedback === -1" @click="sendFeedback(-1)" title="Needs improvement">Improve</button>
              </div>
            </div>
            <div v-else class="text-content">
              <p v-if="sidebar.error" class="inline-error" role="alert">{{ sidebar.error }}</p>
              <template v-if="sidebar.content">
                <div class="result-actions">
                  <button type="button" @click="copyResult">Copy transcript</button>
                  <button type="button" @click="downloadResult">Export text</button>
                </div>
                <p class="text-meta">{{ transcriptMeta }}</p>
                <pre>{{ sidebar.content }}</pre>
              </template>
              <p v-else-if="!sidebar.error" class="text-meta">This video does not have a transcript to show yet.</p>
            </div>
          </div>
        </div>
      </div>

      <div v-if="showAuthModal" class="auth-backdrop" @click.self="closeAuthModal">
        <div
            ref="authPanel"
            class="auth-panel"
            role="dialog"
            aria-modal="true"
            aria-labelledby="auth-title"
            @keydown="trapAuthFocus"
        >
          <div class="auth-header">
            <h2 id="auth-title" class="auth-title">{{ authMode === 'login' ? 'Sign in' : 'Create account' }}</h2>
            <button class="close-btn" @click="closeAuthModal" aria-label="Close sign-in dialog">×</button>
          </div>
          <form class="auth-body" @submit.prevent="handleAuth">
            <div class="input-group">
              <label for="auth-username">Username</label>
              <input id="auth-username" v-model="authForm.username" type="text" placeholder="Enter your username" autocomplete="username" autofocus />
            </div>
            <div class="input-group">
              <label for="auth-password">Password</label>
              <input id="auth-password" v-model="authForm.password" type="password" placeholder="Enter your password" :autocomplete="authMode === 'login' ? 'current-password' : 'new-password'" />
            </div>
            <div class="input-group" v-if="authMode === 'register'">
              <label for="auth-nickname">Display name</label>
              <input id="auth-nickname" v-model="authForm.nickname" type="text" placeholder="Choose a display name" autocomplete="nickname" />
            </div>
            <div class="auth-action">
              <button type="submit" class="cyber-btn" :disabled="authLoading">
                <span v-if="!authLoading">{{ authMode === 'login' ? 'Sign in' : 'Create account' }}</span>
                <span v-else>Working…</span>
              </button>
            </div>
            <div class="auth-toggle">
              <span class="toggle-text">{{ authMode === 'login' ? 'New here?' : 'Already have an account?' }}</span>
              <button type="button" class="toggle-link" @click="switchAuthMode()">{{ authMode === 'login' ? 'Create one' : 'Sign in' }}</button>
            </div>
            <p
                v-if="authMessage"
                class="auth-msg"
                :class="{'error': authError}"
                :role="authError ? 'alert' : 'status'"
                aria-live="polite"
            >{{ authMessage }}</p>
          </form>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch, onMounted, onUnmounted } from 'vue'
import { apiRequest, clearAuthToken, hasAuthToken, setAuthToken } from './api'
import {
  forgetUploadProgress,
  formatBytes,
  formatDurationText,
  hasUploadProgress,
  uploadVideoInChunks,
  validateVideoFile
} from './chunkUpload'
import { DEMO_ITEM } from './demoData'
import { createTaskStreams } from './taskEvents'
import { useAnalysisWorkspace } from './useAnalysisWorkspace'

// --- State declarations ---
const DEMO_MODE = new URLSearchParams(window.location.search).has('demo')
const MESSAGE_TIMEOUT_MS = 4000
const file = ref(null)
const videoUrl = ref('')
const message = ref('')
const messageIsError = ref(false)
const uploading = ref(false)
const uploadProgress = ref({ label: 'Preparing upload', filename: '', percent: null, indeterminate: false, detail: '', warning: '' })
const uploadAbort = ref(null)
const resumableFile = ref(null)
const resumableChunks = ref({ done: 0, total: 0 })
const list = ref([])
const searchQuery = ref('')
const videoPlayer = ref(null)
const sidebarPanel = ref(null)
const sidebarBody = ref(null)
const authPanel = ref(null)
const deletingId = ref(null)
const isOffline = ref(typeof navigator !== 'undefined' && navigator.onLine === false)
const activeTasks = ref([])
const elapsedSeconds = ref(0)
const visibleList = computed(() => {
  const query = searchQuery.value.trim().toLocaleLowerCase()
  if (!query) return list.value
  return list.value.filter(item => item.filename?.toLocaleLowerCase().includes(query))
})
const isDragOver = ref(false)
const currentUser = ref(null)
const showAuthModal = ref(false)
const authMode = ref('login')
const authLoading = ref(false)
const authMessage = ref('')
const authError = ref(false)
const authForm = ref({ username: '', password: '', nickname: '' })
const taskStreams = createTaskStreams({
  onActiveChange: tasks => { activeTasks.value = tasks }
})
const VIDEO_EXTENSIONS = new Set(['mp4', 'mov', 'mkv', 'avi', 'webm', 'm4v'])
let dragDepth = 0
let messageTimer = null
let elapsedTimer = null
let lastUploadProgress = {}
let focusBeforeAuth = null
let focusBeforeSidebar = null

// --- Status display ---
const activeTaskOf = mediaId => activeTasks.value.find(task => String(task.id) === String(mediaId))

const systemStatusText = computed(() => {
  if (isOffline.value) return 'Offline'
  if (uploading.value) {
    return uploadProgress.value.percent !== null
      ? `Uploading ${uploadProgress.value.percent}%`
      : 'Processing'
  }
  if (activeTasks.value.length) return `${activeTasks.value.length} background task(s)`
  return 'Ready'
})
const cancelUploadLabel = computed(() => uploadProgress.value.percent === null
  ? 'Stop waiting'
  : 'Cancel upload')

const cardStatusClass = item => {
  if (activeTaskOf(item.id)) return 'processing'
  return mediaStatusClass(item.status)
}
const cardStatusLabel = item => {
  const task = activeTaskOf(item.id)
  if (task) return task.type === 'ai' ? 'ANALYZING' : 'TRANSCRIBING'
  return mediaStatusLabel(item.status)
}
const cardStatusTitle = item => {
  const task = activeTaskOf(item.id)
  if (!task) return null
  return task.type === 'ai'
    ? 'AI analysis is running in the background. We will notify you when it is ready.'
    : 'Transcription is running in the background. We will notify you when it is ready.'
}
const actionTitle = (item, label) => item.status === 'COMPLETED'
  ? null
  : `This video is still processing, so ${label.toLowerCase()} is unavailable.`

const elapsedLabel = computed(() => {
  const total = elapsedSeconds.value
  if (total < 1) return ''
  const minutes = String(Math.floor(total / 60)).padStart(2, '0')
  return `${minutes}:${String(total % 60).padStart(2, '0')}`
})

const loadingHeadline = computed(() => {
  const fallback = sidebar.value.type === 'ai'
    ? 'The Agent is analyzing video evidence'
    : 'Recognizing speech from the video'
  const headline = sidebar.value.statusMessage || fallback
  // Use elapsed waiting time because resumed tasks are timed from opening the panel.
  return elapsedLabel.value ? `${headline} · waiting ${elapsedLabel.value}` : headline
})

const transcriptMeta = computed(() => {
  const length = sidebar.value.content?.length || 0
  if (!length) return ''
  return `${length.toLocaleString('en-US')} characters`
})

const resumeHint = computed(() => {
  const target = resumableFile.value
  if (!target) return ''
  const { done, total } = resumableChunks.value
  const progress = total ? `${Math.round((done / total) * 100)}% complete` : 'Upload progress is saved'
  return `${target.name}: ${progress}. You can resume the remaining upload.`
})

// --- Core workflow ---

const handleDragEnter = () => {
  dragDepth += 1
  isDragOver.value = true
}

// Dragging across child elements triggers dragleave; use a counter to prevent indicator flicker.
const handleDragLeave = () => {
  dragDepth = Math.max(0, dragDepth - 1)
  if (!dragDepth) isDragOver.value = false
}

const resetDragState = () => {
  dragDepth = 0
  isDragOver.value = false
}

/** Validate sign-in, format, and size before entering the upload state. */
const startUpload = async (selectedFile, extraFileCount = 0) => {
  if (uploading.value) {
    showMsg('An upload is already in progress. Please wait for it to finish.', true)
    return
  }
  if (!currentUser.value) {
    showMsg('Please sign in before uploading.', true)
    openAuthModal()
    return
  }
  if (!selectedFile) return
  if (!isSupportedVideo(selectedFile)) {
    showMsg(`${selectedFile.name} is not a supported video format.`, true)
    return
  }
  const invalid = validateVideoFile(selectedFile)
  if (invalid) {
    showMsg(`⚠️ ${invalid}`, true)
    return
  }
  if (extraFileCount > 0) {
    showMsg(`One video can be processed at a time. Selected ${selectedFile.name}; ignored ${extraFileCount} other file(s).`)
  }
  file.value = selectedFile
  videoUrl.value = ''
  await uploadFile()
}

const handleFileChange = async (e) => {
  const selected = e.target.files
  await startUpload(selected?.[0], Math.max(0, (selected?.length || 0) - 1))
  e.target.value = ''
}

const handleDrop = async (e) => {
  resetDragState()
  const dropped = e.dataTransfer?.files
  if (!dropped?.length) return
  await startUpload(dropped[0], dropped.length - 1)
}

const buildUploadWarning = progress => {
  if (progress.retryingCount) {
    return `Network is unstable. Retrying ${progress.retryingCount} chunk(s), attempt ${progress.retryAttempt}/${progress.retryMaxAttempts}.`
  }
  if (progress.resumedChunks) {
    return `Resuming upload: skipped ${progress.resumedChunks} completed chunk(s).`
  }
  return ''
}

const applyUploadProgress = progress => {
  lastUploadProgress = progress
  const merging = progress.phase === 'merging'
  const detail = [`${formatBytes(progress.uploadedBytes)} / ${formatBytes(progress.totalBytes)}`]
  detail.push(`Chunks ${progress.completedChunks}/${progress.totalChunks}`)
  if (!merging && progress.bytesPerSecond) {
    detail.push(`${formatBytes(progress.bytesPerSecond)}/s`)
    const eta = formatDurationText(progress.etaSeconds)
    if (eta) detail.push(`about ${eta} remaining`)
  }
  uploadProgress.value = {
    label: merging ? 'All chunks received — merging on the server' : 'Uploading securely',
    filename: file.value?.name || uploadProgress.value.filename,
    percent: progress.percent,
    detail: detail.join(' · '),
    warning: buildUploadWarning(progress)
  }
}

const rememberResumableUpload = target => {
  if (!target || !hasUploadProgress(target)) {
    resumableFile.value = null
    return
  }
  resumableFile.value = target
  resumableChunks.value = {
    done: lastUploadProgress.completedChunks || 0,
    total: lastUploadProgress.totalChunks || 0
  }
}

const uploadFile = async () => {
  const target = file.value
  if (!target) return
  if (DEMO_MODE) {
    showMsg('Demo mode: upload completed.')
    return
  }

  const controller = new AbortController()
  uploadAbort.value = controller
  uploading.value = true
  resumableFile.value = null
  lastUploadProgress = {}
  const uploadUserId = currentUser.value?.id
  uploadProgress.value = {
    label: hasUploadProgress(target) ? 'Checking uploaded chunks' : 'Preparing chunked upload',
    filename: target.name,
    percent: 0,
    detail: `0 B / ${formatBytes(target.size)}`,
    warning: ''
  }

  try {
    const uploadedMedia = await uploadVideoInChunks(target, applyUploadProgress, controller.signal)
    if (currentUser.value?.id !== uploadUserId) return
    resumableFile.value = null
    showMsg(`${target.name} uploaded successfully.`)
    await fetchList({ notify: true })
    openAgent(uploadedMedia)
  } catch (error) {
    if (currentUser.value?.id !== uploadUserId) return
    rememberResumableUpload(target)
    if (error?.aborted) {
      showMsg('Upload cancelled. Your progress was saved, so you can resume it later.')
      return
    }
    console.error(error)
    showMsg(
      resumableFile.value
        ? `Upload interrupted: ${error.message} (progress saved — you can resume it)`
        : `Upload failed: ${error.message}`,
      true
    )
  } finally {
    uploading.value = false
    uploadAbort.value = null
    file.value = null
  }
}

const cancelUpload = () => {
  if (!uploadAbort.value) return
  uploadProgress.value = {
    ...uploadProgress.value,
    label: uploadProgress.value.percent === null ? 'Stopping local wait' : 'Cancelling upload',
    warning: ''
  }
  uploadAbort.value.abort()
}

const resumeUpload = async () => {
  const target = resumableFile.value
  if (!target || uploading.value) return
  file.value = target
  await uploadFile()
}

const discardResumableUpload = () => {
  forgetUploadProgress(resumableFile.value)
  resumableFile.value = null
  resumableChunks.value = { done: 0, total: 0 }
  showMsg('Saved upload progress cleared. Your next upload will start from the beginning.')
}

const handleUrlUpload = async () => {
  const normalizedUrl = videoUrl.value.trim()
  if (!normalizedUrl) return
  if (uploading.value) {
    showMsg('An upload is already in progress. Please wait for it to finish.', true)
    return
  }
  if (DEMO_MODE) {
    videoUrl.value = ''
    showMsg('Demo mode: link imported.')
    return
  }

  if (!currentUser.value) {
    showMsg('Please sign in before uploading.', true)
    openAuthModal()
    return
  }

  let parsedUrl
  try {
    parsedUrl = new URL(normalizedUrl)
  } catch {
    parsedUrl = null
  }
  if (!parsedUrl || !['http:', 'https:'].includes(parsedUrl.protocol)) {
    showMsg('Enter a valid http or https link.', true)
    return
  }

  uploading.value = true
  const uploadUserId = currentUser.value?.id
  uploadProgress.value = {
    label: 'Importing video link',
    filename: parsedUrl.hostname,
    percent: null,
    indeterminate: true,
    detail: 'Checking for captions first. Supported videos are ready for text analysis without downloading the full file.',
    warning: ''
  }
  messageIsError.value = false
  message.value = 'Importing the link. Checking captions first…'
  const controller = new AbortController()
  uploadAbort.value = controller
  const formData = new FormData()
  formData.append('url', normalizedUrl)

  try {
    const res = await apiRequest('/media/upload-url', {
      method: 'POST',
      body: formData,
      signal: controller.signal
    })
    if (!res.ok) throw new Error(await res.text())
    const uploadedMedia = await res.json()
    if (currentUser.value?.id !== uploadUserId) return

    showMsg('Video link imported into your library.')
    videoUrl.value = ''
    await fetchList({ notify: true })
    openAgent(uploadedMedia)
  } catch (error) {
    console.error(error)
    if (currentUser.value?.id !== uploadUserId) return
    if (error?.name === 'AbortError') {
      showMsg('Stopped waiting in this browser. The server may still finish its current download.', true)
      return
    }
    let errMsg = error.message
    if (errMsg.includes("Unsupported URL")) errMsg = 'This video platform is not supported.'
    showMsg('Import failed: ' + errMsg, true)
  } finally {
    uploading.value = false
    uploadAbort.value = null
  }
}

/** Success notices dismiss automatically; errors remain until dismissed to preserve failure context. */
const showMsg = (msg, isError = false) => {
  clearTimeout(messageTimer)
  messageTimer = null
  message.value = msg
  messageIsError.value = isError
  if (isError) return
  messageTimer = setTimeout(() => {
    if (message.value !== msg) return
    message.value = ''
    messageIsError.value = false
  }, MESSAGE_TIMEOUT_MS)
}

const dismissMessage = () => {
  if (!messageIsError.value) return
  clearTimeout(messageTimer)
  messageTimer = null
  message.value = ''
  messageIsError.value = false
}

const fetchList = async ({ notify = false } = {}) => {
  if (DEMO_MODE) return list.value
  if (!currentUser.value) {
    list.value = []
    return list.value
  }
  try {
    // Add a timestamp to avoid stale browser caches after a delete or upload.
    const res = await apiRequest(`/media/list?_t=${Date.now()}`)
    if (res.status === 401) return null
    if (!res.ok) throw new Error('Unable to load your video library.')
    list.value = await res.json()
  } catch (error) {
    console.error(error)
    if (notify) showMsg('Unable to load your video library. Please refresh shortly.', true)
    return null
  }
  return list.value
}

const isSupportedVideo = selectedFile => {
  if (selectedFile.type?.startsWith('video/')) return true
  const extension = selectedFile.name?.split('.').pop()?.toLowerCase()
  return VIDEO_EXTENSIONS.has(extension)
}

const mediaStatusClass = status => ['COMPLETED', 'PROCESSING', 'FAILED'].includes(status)
  ? status.toLowerCase()
  : 'unknown'
const mediaStatusLabel = status => ({
  COMPLETED: 'READY',
  PROCESSING: 'PROCESSING',
  FAILED: 'FAILED'
})[status] || 'PENDING'

const {
  sidebar,
  goalPresets,
  analysisModes,
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
  formatPercent
} = useAnalysisWorkspace({
  demoMode: DEMO_MODE,
  taskStreams,
  showMessage: showMsg,
  refreshMediaList: fetchList,
  findMediaItem: id => list.value.find(item => item.id === id),
  onAnswerAppended: () => scrollToLatestAnswer()
})

/** Scroll to appended follow-up answers so users can see the update. */
const scrollToLatestAnswer = async () => {
  await nextTick()
  const container = sidebarBody.value?.querySelector('.markdown-content')
  if (!container) return
  const headings = container.querySelectorAll('h2, h3')
  const anchor = headings.length ? headings[headings.length - 1] : container.lastElementChild
  anchor?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

const seekVideo = seconds => {
  if (!Number.isFinite(seconds)) return
  const player = videoPlayer.value
  if (!player) {
    if (sidebar.value.playbackError) {
      showMsg('The source video could not load, so this timestamp cannot be opened. Try again first.', true)
    } else if (sidebar.value.playbackLoading) {
      showMsg('The source video is still loading. Try this timestamp again in a moment.')
    } else {
      showMsg('This video has no playable source available right now.', true)
    }
    return
  }
  if (player.readyState === 0) {
    player.addEventListener('loadedmetadata', () => seekVideo(seconds), { once: true })
    return
  }
  const duration = player.duration
  const maxTime = Number.isFinite(duration) ? Math.max(0, duration - 0.1) : Number.MAX_SAFE_INTEGER
  player.currentTime = Math.min(Math.max(0, seconds), maxTime)
  player.play().catch(() => {})
  player.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
}

const seekEvidence = event => {
  const link = event.target.closest('a[href^="#video-t="]')
  if (!link) return
  event.preventDefault()
  seekVideo(Number(link.getAttribute('href').split('=')[1]))
}

const seekToEvidence = timestampMs => seekVideo(Number(timestampMs) / 1000)
const formatEvidenceTime = timestampMs => {
  const seconds = Math.max(0, Math.floor(Number(timestampMs) / 1000))
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const time = `${String(minutes).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
  return hours ? `${String(hours).padStart(2, '0')}:${time}` : time
}

/** Keep a fallback because Clipboard API is unavailable in non-HTTPS environments. */
const copyToClipboard = async text => {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    // Continue to the fallback below.
  }
  try {
    const scratch = document.createElement('textarea')
    scratch.value = text
    scratch.setAttribute('readonly', '')
    scratch.style.position = 'fixed'
    scratch.style.top = '0'
    scratch.style.opacity = '0'
    document.body.appendChild(scratch)
    scratch.select()
    const copied = document.execCommand('copy')
    document.body.removeChild(scratch)
    return copied
  } catch {
    return false
  }
}

const copyResult = async () => {
  const content = sidebar.value.content
  if (!content) {
    showMsg('There is no content to copy yet.', true)
    return
  }
  const label = sidebar.value.type === 'ai' ? 'Analysis result' : 'Full transcript'
  if (await copyToClipboard(content)) showMsg(`${label} copied.`)
  else showMsg('Copy failed. Please select and copy the content manually.', true)
}

const resultFileBaseName = () => {
  const title = sidebar.value.title || ''
  const raw = title.split(' · ').slice(1).join(' · ') || title
  const cleaned = raw.replace(/\.[^/.]+$/, '').replace(/[\\/:*?"<>|]/g, '_').trim()
  return cleaned || (sidebar.value.type === 'ai' ? 'analysis' : 'transcript')
}

const downloadResult = () => {
  const content = sidebar.value.content
  if (!content) {
    showMsg('There is no content to export yet.', true)
    return
  }
  const isMarkdown = sidebar.value.type === 'ai'
  const blob = new Blob([content], {
    type: isMarkdown ? 'text/markdown;charset=utf-8' : 'text/plain;charset=utf-8'
  })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${resultFileBaseName()}.${isMarkdown ? 'md' : 'txt'}`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  // Immediate revocation can interrupt downloads in some browsers; defer it one event loop turn.
  setTimeout(() => URL.revokeObjectURL(url), 0)
  showMsg(`Exported ${link.download}`)
}

const deleteItem = async (item) => {
  if (DEMO_MODE) {
    list.value = list.value.filter(i => i.id !== item.id)
    discardMediaWorkspace(item.id)
    showMsg('Demo item removed.')
    return
  }
  if (deletingId.value) return
  const runningTask = activeTaskOf(item.id)
  const warning = runningTask
    ? '\n\nNote: this video still has a background task running. Deleting it will discard that result.'
    : ''
  if (!confirm(`Permanently delete "${item.filename}"?${warning}`)) return
  deletingId.value = item.id
  try {
    const res = await apiRequest(`/media/delete?id=${item.id}`, { method: 'DELETE' })
    const text = await res.text()
    if (res.ok) {
      showMsg(`Deleted ${item.filename}`)
      list.value = list.value.filter(i => i.id !== item.id)
      discardMediaWorkspace(item.id)
    } else {
      showMsg('❌ ' + text, true)
    }
  } catch (e) {
    showMsg('Unable to delete this video.', true)
  } finally {
    deletingId.value = null
  }
}

const formatTime = (timeStr) => {
  if (!timeStr) return '--'
  const date = new Date(timeStr)
  if (Number.isNaN(date.getTime())) return '--'
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

const downloadAudio = async (item) => {
  if (DEMO_MODE) {
    showMsg(`Demo mode: audio for ${item.filename} is ready.`)
    return
  }
  let fileName = item.filename || 'audio.mp3';
  fileName = fileName.replace(/\.[^/.]+$/, "") + ".mp3";
  try {
    showMsg('Converting and downloading audio…')
    const res = await apiRequest(`/analysis/download?id=${item.id}`)
    // api.js exposes the backend envelope message through text(), preserving specific download errors.
    if (!res.ok) throw new Error((await res.text()) || 'Please try again shortly.')
    const blob = await res.blob()
    const downloadUrl = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = downloadUrl
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(downloadUrl)
    showMsg('Download complete.')
  } catch (e) {
    showMsg('Audio download failed: ' + (e?.message || 'please try again shortly.'), true)
  }
}

const restoreFocus = element => {
  if (element?.isConnected && typeof element.focus === 'function') element.focus()
}

const openAuthModal = () => {
  if (showAuthModal.value) return
  focusBeforeAuth = document.activeElement
  showAuthModal.value = true
  authMessage.value = ''
  authForm.value = { username: '', password: '', nickname: '' }
}
const closeAuthModal = () => {
  showAuthModal.value = false
  restoreFocus(focusBeforeAuth)
  focusBeforeAuth = null
}
const closeActiveOverlay = () => {
  if (showAuthModal.value) closeAuthModal()
  else if (sidebar.value.visible) closeSidebar()
}
const handleKeydown = event => {
  if (event.key === 'Escape') closeActiveOverlay()
}

/** Trap Tab within the dialog so keyboard users do not move into obscured background content. */
const trapAuthFocus = event => {
  if (event.key !== 'Tab' || !authPanel.value) return
  const focusable = [...authPanel.value.querySelectorAll('button, input, [tabindex]:not([tabindex="-1"])')]
    .filter(element => !element.disabled && element.offsetParent !== null)
  if (!focusable.length) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  const active = document.activeElement
  if (event.shiftKey && (active === first || !authPanel.value.contains(active))) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && active === last) {
    event.preventDefault()
    first.focus()
  }
}

const switchAuthMode = ({ keepMessage = false } = {}) => {
  authMode.value = authMode.value === 'login' ? 'register' : 'login'
  if (!keepMessage) authMessage.value = ''
}
const handleAuth = async () => {
  if (!authForm.value.username || !authForm.value.password) {
    authMessage.value = 'Enter both your username and password.'
    authError.value = true
    return
  }
  authLoading.value = true
  authMessage.value = ''
  const endpoint = authMode.value === 'login' ? '/user/login' : '/user/register'
  try {
    const res = await apiRequest(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(authForm.value)
    })
    if (!res.ok) {
      authMessage.value = (await res.text()) || `Request failed (HTTP ${res.status})`
      authError.value = true
      return
    }
    const data = await res.json().catch(() => null)
    if (!data?.userInfo) {
      authMessage.value = 'The server returned an unexpected response. Please try again shortly.'
      authError.value = true
      return
    }
    if (authMode.value === 'login') {
      currentUser.value = data.userInfo
      localStorage.setItem('user', JSON.stringify(data.userInfo))
      setAuthToken(data.token)
      closeAuthModal()
      showMsg(`Welcome back, ${data.userInfo.nickname}.`)
      fetchList({ notify: true })
    } else {
      authMessage.value = 'Account created. Your details are still filled in — select Sign in to continue.'
      authError.value = false
      setTimeout(() => switchAuthMode({ keepMessage: true }), 900)
    }
  } catch (e) {
    console.error(e)
    authMessage.value = e?.message || 'Network connection error.'
    authError.value = true
  } finally {
    authLoading.value = false
  }
}
/** Use the same cleanup for logout and expired sessions to avoid divergent state resets. */
const resetSessionState = () => {
  uploadAbort.value?.abort()
  uploadAbort.value = null
  taskStreams.stopAll()
  resetWorkspace()
  currentUser.value = null
  list.value = []
  searchQuery.value = ''
  videoUrl.value = ''
  file.value = null
  resumableFile.value = null
  resumableChunks.value = { done: 0, total: 0 }
  uploading.value = false
  localStorage.removeItem('user')
}

const logout = () => {
  if (hasAuthToken()) {
    apiRequest('/user/logout', { method: 'POST' }).catch(() => {})
  }
  resetSessionState()
  clearAuthToken()
  showMsg('Signed out.')
}

const handleAuthExpired = () => {
  resetSessionState()
  showMsg('Your sign-in session has expired. Please sign in again.', true)
  openAuthModal()
}

const handleOnline = () => {
  isOffline.value = false
  showMsg('Connection restored. Syncing the latest state.')
  if (currentUser.value) fetchList()
}

const handleOffline = () => {
  isOffline.value = true
  showMsg('Connection lost. Uploads will retry automatically and background tasks will continue when you are back online.', true)
}

// Warn before closing a tab during upload so completed chunks are not abandoned unexpectedly.
const handleBeforeUnload = event => {
  if (!uploading.value) return
  event.preventDefault()
  event.returnValue = ''
}

// Mark open overlays; the background-scroll lock applies only on narrow screens.
const overlayOpen = computed(() => sidebar.value.visible || showAuthModal.value)
watch(overlayOpen, open => {
  document.body.classList.toggle('overlay-open', open)
})

watch(() => sidebar.value.visible, async visible => {
  if (visible) {
    focusBeforeSidebar = document.activeElement
    await nextTick()
    sidebarPanel.value?.focus()
    return
  }
  restoreFocus(focusBeforeSidebar)
  focusBeforeSidebar = null
})

// Give long-running tasks a time anchor so they do not appear frozen.
watch(() => sidebar.value.loading, loading => {
  clearInterval(elapsedTimer)
  elapsedTimer = null
  elapsedSeconds.value = 0
  if (!loading) return
  const startedAt = Date.now()
  elapsedTimer = setInterval(() => {
    elapsedSeconds.value = Math.floor((Date.now() - startedAt) / 1000)
  }, 1000)
})

onMounted(() => {
  window.addEventListener('auth-expired', handleAuthExpired)
  window.addEventListener('keydown', handleKeydown)
  window.addEventListener('online', handleOnline)
  window.addEventListener('offline', handleOffline)
  window.addEventListener('beforeunload', handleBeforeUnload)
  if (DEMO_MODE) {
    currentUser.value = { id: 1, nickname: 'Agent Demo' }
    list.value = [DEMO_ITEM]
    openAgent(DEMO_ITEM)
    showDemoResult()
    return
  }
  const savedUser = localStorage.getItem('user')
  if (savedUser && hasAuthToken()) {
    try {
      currentUser.value = JSON.parse(savedUser)
    } catch(e) {}
  }
  fetchList({ notify: Boolean(currentUser.value) })
})
onUnmounted(() => {
  window.removeEventListener('auth-expired', handleAuthExpired)
  window.removeEventListener('keydown', handleKeydown)
  window.removeEventListener('online', handleOnline)
  window.removeEventListener('offline', handleOffline)
  window.removeEventListener('beforeunload', handleBeforeUnload)
  clearTimeout(messageTimer)
  clearInterval(elapsedTimer)
  uploadAbort.value?.abort()
  document.body.classList.remove('overlay-open')
  taskStreams.stopAll()
})
</script>

<style>
/* Fonts load through a non-blocking link in index.html instead of a render-blocking @import. */

:root {
  --bg-deep: #0b0c10;
  --bg-card: #121418;
  --accent-lime: #c5f946;
  --accent-purple: #8a2be2;
  --text-main: #e0e0e0;
  --text-sub: #71757a;
  --text-inverse: #0b0c10;
  --border-tech: #2a2d35;
  --shadow-float: 0 10px 30px -10px rgba(0, 0, 0, 0.7);
  --shadow-glow-lime: 0 0 20px rgba(197, 249, 70, 0.2);
}

* { box-sizing: border-box; margin: 0; padding: 0; }

html, body, #app {
  margin: 0 !important; padding: 0 !important; width: 100vw !important;
  max-width: 100vw !important; min-height: 100vh !important;
  overflow-x: hidden; background-color: var(--bg-deep);
}


.app-stage { position: relative; z-index: 1; width: 100%; min-height: 100vh; color: var(--text-main); font-family: 'Space Grotesk', 'Noto Sans SC', monospace; }

.ambient-noise { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 200 200' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='noiseFilter'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.65' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23noiseFilter)' opacity='0.05'/%3E%3C/svg%3E"); pointer-events: none; z-index: -1; }
.ambient-glow { position: fixed; top: -20%; left: 20%; width: 60vw; height: 60vh; background: radial-gradient(circle, rgba(197, 249, 70, 0.08) 0%, rgba(11, 12, 16, 0) 70%); pointer-events: none; z-index: -2; }

/* Navigation */
.navbar { position: sticky; top: 0; z-index: 100; width: 100%; padding: 1.2rem 0; background: rgba(11, 12, 16, 0.85); backdrop-filter: blur(12px); border-bottom: 1px solid var(--border-tech); }
.nav-content { max-width: 1400px; margin: 0 auto; padding: 0 2rem; display: flex; justify-content: space-between; align-items: center; }
.brand { display: flex; align-items: baseline; gap: 2px; }
.brand-do { font-family: 'Dela Gothic One', sans-serif; font-size: 1.8rem; color: var(--text-main); letter-spacing: -1px; }
.brand-video { font-family: 'Space Grotesk', sans-serif; font-size: 1.8rem; font-weight: 300; }
.beta-badge { font-size: 0.7rem; font-weight: 700; background: var(--accent-lime); color: var(--text-inverse); padding: 2px 6px; border-radius: 2px; margin-left: 8px; transform: translateY(-4px); box-shadow: 0 0 5px var(--accent-lime); }

.nav-controls { display: flex; align-items: center; gap: 15px; }
.auth-btn { background: transparent; border: 1px solid var(--border-tech); color: var(--accent-lime); padding: 6px 16px; border-radius: 4px; font-family: 'Noto Sans SC', sans-serif; font-weight: 700; cursor: pointer; display: flex; align-items: center; gap: 8px; transition: all 0.3s; font-size: 0.85rem; }
.auth-btn:hover { background: rgba(197, 249, 70, 0.1); border-color: var(--accent-lime); box-shadow: 0 0 10px rgba(197, 249, 70, 0.2); }
.user-profile { display: flex; align-items: center; gap: 10px; font-family: monospace; font-size: 0.9rem; color: var(--text-main); }
.user-name { color: var(--accent-lime); }
.logout-btn { background: none; border: none; color: var(--text-sub); cursor: pointer; padding: 4px; display: flex; align-items: center; transition: color 0.3s; }
.logout-btn:hover { color: #ff4757; }

.status-pill { display: flex; align-items: center; gap: 8px; background: var(--bg-card); padding: 6px 12px; border-radius: 4px; border: 1px solid var(--border-tech); font-size: 0.8rem; color: var(--text-sub); }
.status-dot { width: 6px; height: 6px; background: var(--accent-lime); border-radius: 50%; }
.status-pill.is-active .status-dot { animation: pulse-lime 1.5s infinite alternate; }

/* Hero */
.main-container { max-width: 1200px; margin: 0 auto; padding: 4rem 2rem; }
.hero-section { text-align: center; margin-bottom: 6rem; animation: slideUpFade 0.8s forwards; }
.slogan-main { font-family: 'Syncopate', sans-serif; font-size: clamp(2.5rem, 6vw, 4.5rem); font-weight: 700; margin-bottom: 0.5rem; text-shadow: 0 0 20px rgba(197, 249, 70, 0.2); }
.slogan-sub { font-size: 1.1rem; color: var(--text-sub); letter-spacing: 2px; margin-bottom: 3rem; }

/* === Upload wrapper with physical skew === */
.upload-wrapper { max-width: 800px; margin: 0 auto; perspective: 1000px; opacity: 0; animation: slideUpFade 0.8s 0.2s forwards; }

.upload-magnet {
  position: relative; height: 300px;
  background: var(--bg-card);
  border-radius: 16px;
  box-shadow: var(--shadow-float);
  border: 2px solid var(--border-tech);
  overflow: hidden; /* Prevent overflow. */
  transition: all 0.3s;
}
.upload-magnet:hover { border-color: var(--accent-lime); box-shadow: var(--shadow-glow-lime); transform: translateY(-5px); }

/* Container layout */
.split-container {
  display: flex; height: 100%; width: 100%;
  position: relative; overflow: hidden;
}

/* Left and right panels with physical skew */
.skew-pane {
  flex: 1; height: 100%; position: relative; cursor: pointer;
  background: rgba(11, 12, 16, 0.5); /* Original dark base. */
  transition: all 0.4s ease;
  display: flex; align-items: center; justify-content: center;
  z-index: 1;
  /* Skew the container directly instead of using clip-path. */
  transform: skewX(-10deg);
}

/* Extend side panels to cover their edges. */
.pane-local { margin-left: -20px; padding-right: 20px; border-right: 2px solid var(--accent-lime); }
.pane-url { margin-right: -20px; padding-left: 20px; }

/* Hover changes only the background to avoid visual bleed. */
.skew-pane:hover {
  background: rgba(197, 249, 70, 0.05); /* Subtle green background within the skewed panel. */
  z-index: 10;
}

/* Center gap */
.split-gap { width: 4px; background: transparent; transform: skewX(-10deg); }

/* Restore content orientation */
.pane-content {
  /* Apply inverse skew so text remains level. */
  transform: skewX(10deg);
  display: flex; flex-direction: column; align-items: center;
  z-index: 2; transition: transform 0.3s;
}
.skew-pane:hover .pane-content { transform: skewX(10deg) scale(1.05); }

/* Dim the inactive panel */
.split-container:has(.skew-pane:hover) .skew-pane:not(:hover) { opacity: 0.3; filter: grayscale(1); }

.magnet-icon { color: var(--accent-lime); margin-bottom: 1rem; filter: drop-shadow(0 0 5px var(--accent-lime)); }
.magnet-title { font-size: 1.4rem; font-weight: 700; letter-spacing: 1px; margin-bottom: 5px; font-family: 'Dela Gothic One', sans-serif; }
.magnet-desc { font-size: 0.8rem; color: var(--text-sub); font-family: monospace; }

/* URL input with restored orientation */
.url-input-box {
  display: flex; margin-top: 15px; border-bottom: 2px solid var(--border-tech);
  transition: all 0.3s; position: relative; z-index: 30;
}
.skew-pane:hover .url-input-box { border-color: var(--accent-lime); }
.url-input-box input {
  background: transparent; border: none; outline: none; color: var(--text-main);
  font-family: monospace; padding: 8px 5px; width: 180px; font-size: 0.9rem;
}
.url-go-btn {
  background: transparent; border: none; color: var(--accent-lime); cursor: pointer;
  padding: 0 8px; opacity: 0.7; transition: all 0.3s;
}
.url-go-btn:hover { opacity: 1; transform: translateX(3px); }

/* Processing state */
.magnet-content.busy {
  height: 100%; width: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center;
  background: var(--bg-card); position: relative; z-index: 50;
}
.busy-text { margin-top: 15px; color: var(--accent-lime); font-family: monospace; animation: pulse-lime 2s infinite; }
.busy-file { max-width: 80%; margin-top: 8px; color: var(--text-sub); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.upload-progress { width: min(320px, 72%); height: 4px; margin-top: 20px; background: var(--border-tech); overflow: hidden; }
.upload-progress span { display: block; height: 100%; background: var(--accent-lime); transition: width 0.25s ease; }.upload-progress.indeterminate span { width: 38%; animation: indeterminate-progress 1.2s ease-in-out infinite; }
@keyframes indeterminate-progress { from { transform: translateX(-130%); } to { transform: translateX(340%); } }
.busy-stat { max-width: 82%; margin-top: 10px; color: var(--text-sub); font-family: monospace; font-size: 0.78rem; text-align: center; }
.busy-warning { max-width: 82%; margin-top: 8px; color: #ff9aa4; font-family: monospace; font-size: 0.78rem; text-align: center; }
.busy-actions { margin-top: 16px; }
.busy-actions button { border: 1px solid var(--border-tech); border-radius: 4px; background: transparent; color: var(--text-sub); padding: 7px 14px; font-size: 0.8rem; cursor: pointer; transition: all 0.3s; }
.busy-actions button:hover { border-color: #ff4757; color: #ff7c88; }
/* === End upload wrapper === */

.upload-resume {
  margin-top: 16px; display: flex; flex-wrap: wrap; align-items: center; justify-content: center; gap: 10px;
  padding: 12px 16px; background: var(--bg-card); border: 1px solid var(--border-tech);
  border-left: 2px solid var(--accent-lime); color: var(--text-sub); font-size: 0.85rem; text-align: left;
}
.upload-resume button { border: 1px solid var(--border-tech); border-radius: 4px; background: transparent; color: var(--accent-lime); padding: 6px 12px; cursor: pointer; }
.upload-resume button:hover { border-color: var(--accent-lime); background: rgba(197, 249, 70, 0.08); }

.notification-bar { margin-top: 2rem; display: inline-block; background: var(--accent-lime); color: var(--text-inverse); padding: 10px 24px; font-weight: 700; border-radius: 4px; clip-path: polygon(5% 0%, 100% 0%, 95% 100%, 0% 100%); }
.notification-bar.error { background: #ff4757; color: #fff; cursor: pointer; }

.quantum-loader { width: 50px; height: 50px; border: 4px solid var(--border-tech); border-top-color: var(--accent-lime); border-radius: 50%; animation: spin 0.8s linear infinite; margin-bottom: 1rem; box-shadow: 0 0 10px var(--accent-lime); }
.quantum-loader.small { width: 30px; height: 30px; margin: 0 auto; }

/* Workspace */
.workspace-section { opacity: 0; animation: slideUpFade 0.8s 0.4s forwards; }
.section-header { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 2rem; border-bottom: 2px solid var(--border-tech); padding-bottom: 12px; }
.library-title { display: flex; align-items: center; gap: 12px; }
.section-header h3 { font-size: 1.5rem; font-weight: 700; }
.count-chip { background: var(--border-tech); padding: 4px 10px; border-radius: 4px; font-size: 0.75rem; font-family: monospace; }
.library-search { width: min(320px, 42vw); display: flex; align-items: center; gap: 9px; padding: 8px 11px; border: 1px solid var(--border-tech); background: #090a0d; color: var(--text-sub); }
.library-search:focus-within { border-color: var(--accent-lime); color: var(--accent-lime); }
.library-search input { width: 100%; border: 0; outline: 0; background: transparent; color: var(--text-main); font: inherit; }
.library-empty { padding: 52px 20px; text-align: center; color: var(--text-sub); border: 1px dashed var(--border-tech); }
.library-empty button { margin-top: 12px; border: 0; background: transparent; color: var(--accent-lime); cursor: pointer; }
.card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 20px; }
.project-card { background: var(--bg-card); border-radius: 12px; box-shadow: 0 2px 10px rgba(0,0,0,0.3); border: 1px solid var(--border-tech); overflow: hidden; transition: transform 0.2s; position: relative; }
.project-card:hover { transform: translateY(-2px); border-color: var(--accent-lime); }
.card-meta { display: flex; gap: 1.5rem; padding: 1.5rem; align-items: center; border-bottom: 1px solid var(--border-tech); background: rgba(18, 21, 18, 0.5); }
.meta-icon { width: 56px; height: 56px; background: rgba(197, 249, 70, 0.05); border: 1px solid var(--accent-lime); border-radius: 8px; display: flex; align-items: center; justify-content: center; color: var(--accent-lime); }
.filename-mask { font-size: 1.1rem; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 180px; }
.meta-tags { display: flex; gap: 12px; font-size: 0.85rem; font-family: monospace; margin-top: 5px; }
.time-tag { color: var(--text-sub); }
.status-indicator { font-weight: 600; padding: 2px 8px; border-radius: 4px; }
.status-indicator.completed { color: var(--accent-lime); border: 1px solid var(--accent-lime); background: rgba(197, 249, 70, 0.1); }
.status-indicator.processing { color: var(--accent-purple); border: 1px solid var(--accent-purple); animation: blink 1s infinite; }
.status-indicator.failed { color: #ff7c88; border: 1px solid #ff4757; background: rgba(255, 71, 87, 0.08); }
.status-indicator.unknown { color: var(--text-sub); border: 1px solid var(--border-tech); }

.action-dock { display: grid; grid-template-columns: 1fr 1fr 1.5fr; gap: 12px; padding: 12px; background: rgba(5, 8, 5, 0.5); }
.dock-item { position: relative; border: 1px solid var(--border-tech); background: var(--bg-card); border-radius: 8px; padding: 16px; display: flex; align-items: center; justify-content: center; gap: 10px; cursor: pointer; transition: all 0.3s; color: var(--text-sub); font-family: monospace; overflow: hidden; }
.dock-item:hover:not(:disabled) { color: var(--accent-lime); border-color: var(--accent-lime); background: rgba(197, 249, 70, 0.05); }
.dock-item:disabled { opacity: 0.3; cursor: not-allowed; }
.dock-item.ai-core { border-color: var(--accent-purple); color: var(--accent-purple); }
.dock-item.ai-core .label-group { display: flex; flex-direction: column; align-items: flex-start; z-index: 1; }
.dock-item.ai-core .item-sub { font-size: 0.75rem; color: var(--accent-purple); opacity: 0.8; }
.dock-item.ai-core:hover:not(:disabled) { border-color: var(--accent-lime); color: var(--text-inverse); background: var(--accent-lime); }
.dock-item.ai-core:hover:not(:disabled) .item-sub { color: var(--text-inverse); }

/* Sidebar */
.sidebar-backdrop { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.6); backdrop-filter: blur(4px); z-index: 998; }
.sidebar-panel { position: fixed; top: 0; right: -920px; width: 880px; max-width: calc(100vw - 24px); height: 100%; background: var(--bg-card); border-left: 2px solid var(--accent-lime); z-index: 999; transition: right 0.4s cubic-bezier(0.19, 1, 0.22, 1); display: flex; flex-direction: column; box-shadow: -10px 0 40px rgba(0,0,0,0.8); }
.sidebar-panel.is-open { right: 0; }
.sidebar-header { padding: 20px 30px; border-bottom: 1px solid var(--border-tech); display: flex; justify-content: space-between; align-items: center; background: rgba(11, 12, 16, 0.9); }
.sidebar-title { font-size: 1.4rem; font-weight: 700; color: var(--text-main); display: flex; align-items: center; gap: 10px; }
.icon { color: var(--accent-lime); display: flex; align-items: center; }
.close-btn { width: 40px; height: 40px; background: none; border: none; color: var(--text-sub); cursor: pointer; transition: color 0.3s; font-size: 1.35rem; }
.close-btn:hover { color: var(--accent-lime); }
.sidebar-body { flex: 1; overflow-y: auto; padding: 30px; }
.loading-state { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; color: var(--text-sub); gap: 20px; }
.markdown-content, .text-content { line-height: 1.8; color: var(--text-main); font-size: 0.95rem; }
.text-content pre { white-space: pre-wrap; font-family: monospace; background: #000; padding: 15px; border-radius: 8px; border: 1px solid var(--border-tech); color: #ccc; }
.text-meta { margin-bottom: 10px; color: var(--text-sub); font-family: monospace; font-size: 0.8rem; }
.markdown-content h1, .markdown-content h2, .markdown-content h3 { color: var(--accent-lime); margin-top: 1.5em; margin-bottom: 0.5em; font-family: 'Space Grotesk', sans-serif; }
.markdown-content h1 { border-bottom: 1px solid var(--border-tech); padding-bottom: 10px; }
.markdown-content ul { padding-left: 20px; }
.markdown-content li { margin-bottom: 8px; color: #d4d4d8; }
.markdown-content strong { color: var(--accent-lime); font-weight: 700; }
.markdown-content p { margin-bottom: 1em; }
.markdown-content a[href^="#video-t="] { display: inline-block; padding: 1px 6px; border: 1px solid rgba(197, 249, 70, 0.45); color: var(--accent-lime); text-decoration: none; font-family: monospace; }
.markdown-content a[href^="#video-t="]:hover { background: var(--accent-lime); color: var(--text-inverse); }
.result-actions { display: flex; justify-content: flex-end; gap: 8px; margin-bottom: 18px; }
.result-actions button { border: 1px solid var(--border-tech); background: transparent; color: var(--text-sub); padding: 8px 10px; cursor: pointer; }
.result-actions button:hover:not(:disabled) { border-color: var(--accent-lime); color: var(--accent-lime); }
.result-actions button:disabled { opacity: 0.4; cursor: not-allowed; }

/* Agent workspace */
.video-evidence { margin: -30px -30px 28px; background: #050607; border-bottom: 1px solid var(--border-tech); }
.video-evidence video { display: block; width: 100%; max-height: 420px; aspect-ratio: 16 / 9; background: #000; object-fit: contain; }
.video-evidence p, .video-evidence-loading { padding: 10px 30px; color: var(--text-sub); font-size: 0.8rem; }
.video-evidence-loading { min-height: 110px; display: grid; place-items: center; }
.video-evidence-error { min-height: 110px; padding: 18px 30px; display: flex; align-items: center; justify-content: center; gap: 12px; color: #ff9aa4; }
.video-evidence-error button { border: 1px solid #ff4757; background: transparent; color: #ff9aa4; padding: 6px 10px; border-radius: 4px; cursor: pointer; }
.agent-composer { display: flex; flex-direction: column; gap: 18px; }
.agent-caption { color: var(--text-sub); line-height: 1.7; }
.inline-error { padding: 11px 12px; border-left: 2px solid #ff4757; background: rgba(255, 71, 87, 0.08); color: #ff9aa4; line-height: 1.5; }
.agent-composer textarea, .follow-up-box textarea {
  width: 100%; min-height: 130px; resize: vertical; background: #090a0d; color: var(--text-main);
  border: 1px solid var(--border-tech); border-radius: 6px; padding: 14px; line-height: 1.6; outline: none;
}
.agent-composer textarea:focus, .follow-up-box textarea:focus { border-color: var(--accent-lime); }
.field-counter { color: var(--text-sub); font-family: monospace; font-size: 0.76rem; text-align: right; }
.goal-presets { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.goal-presets button, .feedback-row button {
  border: 1px solid var(--border-tech); border-radius: 4px; background: transparent; color: var(--text-sub);
  padding: 7px 10px; cursor: pointer;
}
.goal-presets button { min-height: 82px; padding: 12px; text-align: left; }
.goal-presets strong, .goal-presets span { display: block; }
.goal-presets strong { margin-bottom: 6px; color: var(--text-main); }
.goal-presets span { font-size: 0.76rem; line-height: 1.45; }
.goal-presets button:hover, .goal-presets button.active, .feedback-row button:hover, .feedback-row button.active {
  color: var(--accent-lime); border-color: var(--accent-lime); background: rgba(197, 249, 70, 0.08);
}
.goal-presets button:hover strong, .goal-presets button.active strong { color: var(--accent-lime); }
.agent-run-btn {
  border: 0; border-radius: 4px; padding: 13px 18px; background: var(--accent-lime); color: var(--text-inverse);
  font-weight: 700; cursor: pointer;
}
.agent-run-btn:disabled, .follow-up-box button:disabled { opacity: 0.4; cursor: not-allowed; }
.evidence-search { margin: 18px 0 24px; }
.evidence-search-form { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; }
.evidence-search-form input {
  min-width: 0; border: 1px solid var(--border-tech); border-radius: 4px; background: #090a0d;
  color: var(--text-main); padding: 10px 12px; outline: none;
}
.evidence-search-form input:focus { border-color: var(--accent-lime); }
.evidence-search-form button, .evidence-search-results button {
  border: 1px solid var(--border-tech); border-radius: 4px; background: transparent;
  color: var(--text-sub); padding: 9px 11px; cursor: pointer;
}
.evidence-search-form button:hover, .evidence-search-results button:hover { border-color: var(--accent-lime); color: var(--accent-lime); }
.evidence-search-form button:disabled { opacity: 0.4; cursor: not-allowed; }
.evidence-search-error { margin-top: 8px; color: #ff9aa4; font-size: 0.82rem; }
.evidence-search-results { display: grid; gap: 6px; margin-top: 8px; }
.evidence-search-results button { display: grid; grid-template-columns: 58px 70px minmax(0, 1fr); gap: 10px; text-align: left; }
.evidence-search-results strong { color: var(--accent-lime); }
.evidence-search-results small { color: var(--accent-purple); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.evidence-search-results span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.agent-running { display: flex; flex-direction: column; gap: 20px; }
.agent-running .loading-state { min-height: 210px; height: auto; }
.loading-state p { max-width: 34rem; text-align: center; }
.stream-offline { color: #ff9aa4; font-family: monospace; font-size: 0.82rem; }
.loading-hint { color: var(--text-sub); font-size: 0.8rem; opacity: 0.85; }
.agent-inspector { margin-top: 28px; border-top: 1px solid var(--border-tech); padding-top: 16px; }
.agent-inspector summary { color: var(--text-sub); cursor: pointer; font-weight: 600; padding: 8px 0; }
.agent-inspector summary:hover { color: var(--accent-lime); }
.agent-inspector-content { padding-top: 14px; }
.agent-meta-block { margin-bottom: 18px; padding: 14px; background: #0c0e12; border-left: 2px solid var(--accent-lime); }
.meta-label { display: block; color: var(--accent-lime); font-size: 0.78rem; font-weight: 700; margin-bottom: 10px; }
.agent-meta-block ol { padding-left: 20px; color: #c9cbd0; }
.agent-meta-block li { margin: 7px 0; }
.plan-editor { display: grid; gap: 8px; margin-top: 12px; }
.plan-editor-row { display: grid; grid-template-columns: minmax(0, 1fr) 34px; gap: 8px; }
.plan-editor input { min-width: 0; border: 1px solid var(--border-tech); background: #090b0e; color: var(--text-main); padding: 9px 10px; }
.plan-editor button, .plan-edit-trigger { border: 1px solid var(--border-tech); background: transparent; color: var(--text-sub); padding: 7px 10px; cursor: pointer; }
.plan-editor button:hover, .plan-edit-trigger:hover { color: var(--accent-lime); border-color: var(--accent-lime); }
.plan-editor-actions { display: flex; justify-content: flex-end; gap: 8px; }
.plan-edit-trigger { margin-top: 8px; }
.stage-list { display: flex; flex-wrap: wrap; gap: 8px; }
.stage-list span, .quality-row span {
  border: 1px solid var(--border-tech); border-radius: 4px; padding: 6px 8px; color: var(--text-sub); font-size: 0.78rem;
}
.quality-row { display: flex; flex-wrap: wrap; gap: 8px; }
.follow-up-box { display: grid; grid-template-columns: 1fr auto; gap: 10px; margin-top: 24px; }
.follow-up-box textarea { min-height: 76px; }
.follow-up-box button {
  align-self: stretch; min-width: 76px; border: 1px solid var(--accent-lime); border-radius: 4px;
  background: rgba(197, 249, 70, 0.08); color: var(--accent-lime); cursor: pointer;
}
.feedback-row { display: flex; align-items: center; gap: 8px; margin-top: 18px; color: var(--text-sub); font-size: 0.85rem; }

/* Authentication dialog */
.auth-backdrop { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.8); backdrop-filter: blur(5px); z-index: 2000; display: flex; justify-content: center; align-items: center; }
.auth-panel { width: 400px; max-width: 90vw; background: var(--bg-card); border: 1px solid var(--border-tech); border-top: 2px solid var(--accent-lime); box-shadow: 0 20px 50px rgba(0,0,0,0.8); display: flex; flex-direction: column; animation: slideUpFade 0.3s forwards; }
.auth-header { padding: 20px; border-bottom: 1px solid var(--border-tech); display: flex; justify-content: space-between; align-items: center; background: rgba(11,12,16,0.9); }
.auth-title { font-family: 'Noto Sans SC', sans-serif; font-size: 1.2rem; color: var(--text-main); font-weight: 700; letter-spacing: 1px; }
.auth-body { padding: 30px; }
.input-group { margin-bottom: 20px; }
.input-group label { display: block; font-family: 'Noto Sans SC', monospace; color: var(--text-sub); font-size: 0.75rem; margin-bottom: 8px; letter-spacing: 1px; }
.input-group input { width: 100%; background: #000; border: 1px solid var(--border-tech); padding: 12px; color: var(--text-main); font-family: monospace; font-size: 1rem; outline: none; transition: all 0.3s; }
.input-group input:focus { border-color: var(--accent-lime); box-shadow: 0 0 10px rgba(197, 249, 70, 0.2); }
.cyber-btn { width: 100%; background: var(--text-main); color: var(--bg-deep); border: none; padding: 12px; font-weight: 700; font-family: 'Noto Sans SC', sans-serif; cursor: pointer; transition: all 0.3s; clip-path: polygon(5% 0%, 100% 0%, 95% 100%, 0% 100%); margin-bottom: 20px; }
.cyber-btn:hover:not(:disabled) { background: var(--accent-lime); color: var(--text-inverse); box-shadow: 0 0 20px rgba(197, 249, 70, 0.4); }
.cyber-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.auth-toggle { text-align: center; font-size: 0.85rem; font-family: 'Noto Sans SC', sans-serif; color: var(--text-sub); }
.toggle-link { background: none; border: none; color: var(--accent-lime); cursor: pointer; font-weight: 700; margin-left: 5px; text-decoration: underline; }
.toggle-link:hover { color: #fff; }
.auth-msg { margin-top: 15px; text-align: center; font-family: 'Noto Sans SC', monospace; font-size: 0.8rem; color: var(--accent-lime); }
.auth-msg.error { color: #ff4757; }

/* Delete button */
.delete-btn {
  position: absolute; top: 10px; right: 10px; background: transparent; border: none;
  width: 36px; height: 36px; color: #71757a; cursor: pointer; opacity: 0; transition: color 0.2s ease, opacity 0.2s ease; z-index: 10;
}
.project-card:hover .delete-btn, .delete-btn:focus-visible { opacity: 1; }
.delete-btn:hover:not(:disabled) { color: #ff4757; }
.delete-btn:disabled { cursor: progress; opacity: 0.5; }
.url-input-box input:disabled, .url-go-btn:disabled { opacity: 0.5; cursor: not-allowed; }

@media (max-width: 720px) {
  .navbar { padding: 0.8rem 0; }
  .nav-content { padding: 0 1rem; }
  .brand-do, .brand-video { font-size: 1.25rem; }
  .status-pill { display: none; }
  .auth-btn { padding: 6px 10px; }
  .main-container { padding: 2.5rem 1rem; }
  .hero-section { margin-bottom: 3rem; }
  .slogan-main { font-size: 2rem; }
  .slogan-sub { margin-bottom: 2rem; }
  .upload-magnet { height: auto; min-height: 420px; border-radius: 8px; }
  .split-container { flex-direction: column; }
  .skew-pane, .pane-local, .pane-url { min-height: 210px; margin: 0; padding: 0; transform: none; }
  .pane-local { border-right: 0; border-bottom: 1px solid var(--accent-lime); }
  .pane-content, .skew-pane:hover .pane-content { transform: none; }
  .split-gap { display: none; }
  .card-grid { grid-template-columns: 1fr; }
  .section-header { align-items: stretch; flex-direction: column; }
  .library-search { width: 100%; }
  .action-dock { grid-template-columns: 1fr; }
  .filename-mask { max-width: 55vw; }
  .sidebar-panel { width: 100%; max-width: 100vw; right: -100vw; }
  .sidebar-header { padding: 16px 18px; }
  .sidebar-title { font-size: 1rem; max-width: calc(100vw - 70px); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .sidebar-body { padding: 20px 16px; }
  .video-evidence { margin: -20px -16px 22px; }
  .video-evidence p, .video-evidence-loading { padding-left: 16px; padding-right: 16px; }
  .goal-presets { grid-template-columns: 1fr; }
  .goal-presets button { min-height: 68px; }
  .result-actions { justify-content: stretch; }
  .result-actions button { flex: 1; padding: 9px 4px; font-size: 0.76rem; }
  .evidence-search-form { grid-template-columns: 1fr; }
  .evidence-search-results button { grid-template-columns: 56px minmax(0, 1fr); }
  .evidence-search-results small { display: none; }
  .follow-up-box { grid-template-columns: 1fr; }
  .follow-up-box button { min-height: 44px; }
  .delete-btn { opacity: 1; }
  .upload-resume { flex-direction: column; align-items: stretch; text-align: center; }
  .upload-resume button { min-height: 40px; }
  .busy-stat, .busy-warning { max-width: 92%; }
  /*
    A full-screen mobile panel should not leave its background scrolling.
    Apply this only on narrow screens: desktop scrollbar width would shift the layout.
  */
  body.overlay-open { overflow: hidden; }
}

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation-duration: 0.01ms !important; animation-iteration-count: 1 !important; scroll-behavior: auto !important; transition-duration: 0.01ms !important; }
}

@keyframes spin { to { transform: rotate(360deg); } }
@keyframes slideUpFade { from { opacity: 0; transform: translateY(40px); } to { opacity: 1; transform: translateY(0); } }
@keyframes pulse-lime { 0% { opacity: 0.5; box-shadow: 0 0 5px var(--accent-lime); } 100% { opacity: 1; box-shadow: 0 0 15px var(--accent-lime); } }
@keyframes blink { 50% { opacity: 0.5; } }

/* 2026 product refresh: calm editorial layout, with the existing interaction model intact. */
:root {
  --bg-deep: #0a0d14;
  --bg-card: #121824;
  --accent-lime: #90f2ba;
  --accent-purple: #a78bfa;
  --text-main: #f5f7fb;
  --text-sub: #9ca9bb;
  --text-inverse: #07100d;
  --border-tech: rgba(166, 184, 211, .16);
  --shadow-float: 0 18px 50px rgba(0, 0, 0, .26);
}

html, body, #app { background: #0a0d14; }
.app-stage { font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans SC", sans-serif; }
.ambient-noise { opacity: .35; }
.ambient-glow { top: -32vh; left: -8vw; width: 74vw; height: 74vh; background: radial-gradient(circle, rgba(94, 234, 170, .12), transparent 66%); }
.app-stage::after { content: ''; position: fixed; inset: auto -15vw -38vh 30vw; height: 72vh; z-index: -2; pointer-events: none; background: radial-gradient(circle, rgba(98, 118, 255, .13), transparent 66%); }

.navbar { padding: 18px 0; background: rgba(10, 13, 20, .76); border-color: rgba(255, 255, 255, .08); }
.nav-content { max-width: 1240px; }
.brand { gap: 0; }
.brand-do, .brand-video { font-family: Inter, ui-sans-serif, sans-serif; font-size: 1.35rem; letter-spacing: -.02em; }
.brand-do { font-weight: 800; color: #fff; letter-spacing: .01em; }
.brand-video { font-weight: 400; color: #9ca9bb; }
.beta-badge { margin-left: 10px; padding: 3px 7px; border-radius: 999px; background: rgba(144, 242, 186, .13); color: var(--accent-lime); box-shadow: none; transform: none; letter-spacing: .08em; }
.auth-btn { min-height: 36px; border-radius: 999px; padding: 7px 14px; color: #07100d; background: var(--accent-lime); border-color: var(--accent-lime); }
.auth-btn:hover { background: #b8f8d2; box-shadow: 0 8px 22px rgba(144, 242, 186, .18); }
.status-pill { border-radius: 999px; background: rgba(255, 255, 255, .04); }

.main-container { max-width: 1240px; padding: clamp(54px, 8vw, 108px) 28px 84px; }
.hero-section { max-width: 920px; margin: 0 auto 84px; }
.hero-eyebrow { display: flex; align-items: center; justify-content: center; gap: 8px; margin-bottom: 22px; color: var(--accent-lime); font-size: 11px; font-weight: 700; letter-spacing: .14em; }
.hero-eyebrow span { width: 7px; height: 7px; border-radius: 50%; background: var(--accent-lime); box-shadow: 0 0 0 5px rgba(144, 242, 186, .12); }
.slogan-main { max-width: 860px; margin: 0 auto 20px; font-family: Inter, "Noto Sans SC", sans-serif; font-size: clamp(40px, 6vw, 76px); font-weight: 750; line-height: 1.08; letter-spacing: -.07em; text-shadow: none; }
.slogan-main em { display: block; font-style: normal; color: #98a4ff; }
.slogan-sub { max-width: 580px; margin: 0 auto 25px; color: #aeb9c9; font-size: 16px; line-height: 1.8; letter-spacing: 0; }
.hero-signals { display: flex; flex-wrap: wrap; justify-content: center; gap: 8px; margin: 0 0 38px; }
.hero-signals span { padding: 6px 10px; border: 1px solid rgba(161, 176, 205, .2); border-radius: 999px; color: #aeb9c9; font-size: 12px; background: rgba(255,255,255,.025); }

.upload-wrapper { max-width: 940px; perspective: none; }
.upload-magnet { height: 294px; overflow: visible; border: 1px solid rgba(161, 176, 205, .18); border-radius: 22px; background: linear-gradient(135deg, rgba(24, 33, 48, .96), rgba(14, 19, 30, .96)); box-shadow: var(--shadow-float); }
.upload-magnet::before { content: ''; position: absolute; inset: -1px; z-index: -1; border-radius: inherit; padding: 1px; background: linear-gradient(130deg, rgba(144, 242, 186, .45), transparent 32%, transparent 70%, rgba(152, 164, 255, .35)); -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0); -webkit-mask-composite: xor; mask-composite: exclude; }
.upload-magnet:hover { border-color: rgba(144, 242, 186, .48); transform: translateY(-3px); box-shadow: 0 26px 66px rgba(0, 0, 0, .31); }
.split-container { overflow: hidden; border-radius: inherit; }
.skew-pane, .pane-local, .pane-url { margin: 0; padding: 0; transform: none; background: transparent; }
.pane-local { border-right: 1px solid rgba(161, 176, 205, .13); }
.skew-pane:hover { background: rgba(144, 242, 186, .045); }
.pane-content, .skew-pane:hover .pane-content { transform: none; transition: transform .24s ease; }
.skew-pane:hover .pane-content { transform: translateY(-4px); }
.split-container:has(.skew-pane:hover) .skew-pane:not(:hover) { opacity: .64; filter: none; }
.split-gap { display: none; }
.magnet-icon { width: 54px; height: 54px; display: grid; place-items: center; margin-bottom: 15px; border: 1px solid rgba(144, 242, 186, .25); border-radius: 16px; color: var(--accent-lime); background: rgba(144, 242, 186, .08); filter: none; }
.magnet-title { margin-bottom: 7px; font-family: Inter, sans-serif; font-size: 15px; letter-spacing: -.02em; }
.magnet-desc { font-family: inherit; font-size: 13px; }
.url-input-box { width: min(245px, 78%); margin-top: 16px; padding: 2px 3px 2px 12px; border: 1px solid rgba(161, 176, 205, .22); border-radius: 10px; background: rgba(4, 7, 12, .35); }
.skew-pane:hover .url-input-box { border-color: rgba(144, 242, 186, .7); }
.url-input-box input { width: 100%; padding: 8px 2px; font-family: inherit; font-size: 12px; }
.url-go-btn { display: grid; place-items: center; width: 31px; height: 31px; border-radius: 7px; color: #07100d; background: var(--accent-lime); opacity: 1; }
.url-go-btn:hover { background: #b8f8d2; transform: none; }
.magnet-content.busy { border-radius: inherit; background: transparent; }
.upload-progress { border-radius: 999px; background: rgba(161, 176, 205, .16); }
.upload-progress span { background: linear-gradient(90deg, var(--accent-lime), #9eb2ff); }
.upload-resume { border: 1px solid rgba(161, 176, 205, .16); border-radius: 14px; background: rgba(255,255,255,.025); }
.notification-bar { border-radius: 999px; clip-path: none; box-shadow: 0 10px 24px rgba(144,242,186,.14); }

.workspace-section { max-width: 1120px; margin: 0 auto; }
.section-header { margin-bottom: 22px; padding-bottom: 16px; border-bottom: 1px solid rgba(161, 176, 205, .16); }
.section-header h3 { font-size: 20px; letter-spacing: -.04em; }
.count-chip { border-radius: 999px; background: rgba(152, 164, 255, .13); color: #bec7ff; }
.library-search { border-radius: 10px; background: rgba(255,255,255,.025); }
.card-grid { gap: 16px; }
.project-card { border: 1px solid rgba(161, 176, 205, .15); border-radius: 16px; background: rgba(18, 24, 36, .78); box-shadow: none; }
.project-card:hover { border-color: rgba(144, 242, 186, .5); box-shadow: 0 18px 38px rgba(0,0,0,.2); transform: translateY(-4px); }
.card-meta { gap: 14px; padding: 18px; background: transparent; border-color: rgba(161, 176, 205, .12); }
.meta-icon { width: 46px; height: 46px; border-color: rgba(152, 164, 255, .32); border-radius: 13px; color: #bec7ff; background: rgba(152, 164, 255, .1); }
.filename-mask { font-size: 15px; max-width: 205px; }
.action-dock { gap: 8px; padding: 10px; background: rgba(5,8,14,.18); }
.dock-item { min-height: 50px; padding: 10px; border-radius: 10px; background: rgba(255,255,255,.025); font-family: inherit; font-size: 12px; }
.dock-item.ai-core { border-color: rgba(144, 242, 186, .45); color: var(--accent-lime); background: rgba(144, 242, 186, .07); }
.dock-item.ai-core:hover:not(:disabled) { background: var(--accent-lime); color: #07100d; }

.sidebar-backdrop, .auth-backdrop { background: rgba(3, 6, 11, .68); backdrop-filter: blur(10px); }
.sidebar-panel { border-left: 1px solid rgba(161, 176, 205, .2); background: #101622; box-shadow: -24px 0 70px rgba(0,0,0,.35); }
.sidebar-header { background: rgba(16, 22, 34, .9); border-color: rgba(161, 176, 205, .14); }
.auth-panel { border: 1px solid rgba(161, 176, 205, .2); border-top: 1px solid rgba(144, 242, 186, .7); border-radius: 18px; overflow: hidden; box-shadow: 0 28px 80px rgba(0,0,0,.45); }
.cyber-btn { border-radius: 10px; clip-path: none; background: var(--accent-lime); }

@media (max-width: 720px) {
  .main-container { padding: 46px 18px 60px; }
  .hero-section { margin-bottom: 52px; }
  .hero-eyebrow { font-size: 10px; }
  .slogan-main { font-size: clamp(36px, 11vw, 49px); letter-spacing: -.075em; }
  .slogan-sub { font-size: 14px; }
  .hero-signals { margin-bottom: 26px; }
  .upload-magnet { min-height: 0; height: auto; border-radius: 18px; }
  .split-container { flex-direction: column; }
  .skew-pane, .pane-local, .pane-url { min-height: 190px; }
  .pane-local { border-right: 0; border-bottom: 1px solid rgba(161,176,205,.15); }
  .card-grid { gap: 12px; }
}

/* VideoMind-AI light system */
:root { --bg-deep: #f8fafc; --bg-card: #ffffff; --accent-lime: #176b4d; --accent-purple: #5b63c8; --text-main: #14213a; --text-sub: #64748b; --text-inverse: #ffffff; --border-tech: rgba(20, 33, 58, .12); --shadow-float: 0 20px 55px rgba(33, 54, 86, .09); }
html, body, #app { background: #f8fafc; }
.ambient-noise { opacity: .08; mix-blend-mode: multiply; }
.ambient-glow { background: radial-gradient(circle, rgba(163, 230, 190, .35), transparent 67%); }
.app-stage::after { background: radial-gradient(circle, rgba(205, 213, 255, .42), transparent 68%); }
.navbar { background: rgba(255, 255, 255, .82); border-color: rgba(20, 33, 58, .09); }
.brand-do { color: #14213a; }.brand-video { color: #64748b; }.beta-badge { background: #e4f6ed; color: #176b4d; }.status-pill { background: #f8fafc; }
.auth-btn { color: #fff; background: #176b4d; border-color: #176b4d; }.auth-btn:hover { background: #0f5138; }
.slogan-main { color: #14213a; }.slogan-main em { color: #5b63c8; }.slogan-sub { color: #53647d; }.hero-signals span { color: #53647d; border-color: rgba(20,33,58,.13); background: rgba(255,255,255,.72); }
.upload-magnet { background: linear-gradient(135deg, #ffffff, #f5f8fc); border-color: rgba(20,33,58,.13); }.upload-magnet:hover { border-color: rgba(23,107,77,.55); box-shadow: 0 26px 60px rgba(33,54,86,.12); }.upload-magnet::before { background: linear-gradient(130deg, rgba(23,107,77,.28), transparent 32%, transparent 70%, rgba(91,99,200,.25)); }.pane-local { border-color: rgba(20,33,58,.1); }.skew-pane:hover { background: rgba(23,107,77,.04); }.magnet-icon { color: #176b4d; background: #effaf3; border-color: #c3e9d2; }.magnet-desc { color: #64748b; }.url-input-box { border-color: rgba(20,33,58,.15); background: #fff; }.url-input-box input { color: #14213a; }.url-go-btn { color: #fff; background: #176b4d; }.url-go-btn:hover { background: #0f5138; }
.workspace-section, .section-header h3, .filename-mask { color: #14213a; }.section-header { border-color: rgba(20,33,58,.11); }.count-chip { color: #5159bb; background: #eef0ff; }.library-search { background: #fff; border-color: rgba(20,33,58,.14); }.project-card { background: #fff; border-color: rgba(20,33,58,.12); }.project-card:hover { border-color: rgba(23,107,77,.45); box-shadow: 0 16px 36px rgba(33,54,86,.1); }.card-meta { border-color: rgba(20,33,58,.1); }.meta-icon { color: #5159bb; background: #f1f2ff; border-color: #d7daf8; }.action-dock { background: #fafcff; }.dock-item { background: #fff; }.dock-item.ai-core { color: #176b4d; border-color: #b6e3c8; background: #effaf3; }.dock-item.ai-core:hover:not(:disabled) { color: #fff; background: #176b4d; }
.sidebar-backdrop, .auth-backdrop { background: rgba(20,33,58,.28); }.sidebar-panel, .sidebar-header { background: #fff; border-color: rgba(20,33,58,.12); box-shadow: -24px 0 70px rgba(33,54,86,.16); }.auth-panel { background: #fff; border-color: rgba(20,33,58,.14); border-top-color: #176b4d; }.auth-header, .auth-body { background: #fff; }.auth-body .input-group input { background: #fff; color: #14213a; border-color: rgba(20,33,58,.2); box-shadow: none; }.auth-body .input-group input::placeholder { color: #94a3b8; }.auth-body .input-group input:focus { border-color: #176b4d; box-shadow: 0 0 0 3px rgba(23,107,77,.12); }.cyber-btn { color: #fff; background: #176b4d; }
.agent-caption, .field-counter { color: #53647d; }.agent-composer textarea, .follow-up-box textarea, .evidence-search-form input { background: #fff; color: #14213a; border-color: rgba(20,33,58,.2); box-shadow: none; }.agent-composer textarea::placeholder, .follow-up-box textarea::placeholder, .evidence-search-form input::placeholder { color: #94a3b8; }.agent-composer textarea:focus, .follow-up-box textarea:focus, .evidence-search-form input:focus { border-color: #176b4d; box-shadow: 0 0 0 3px rgba(23,107,77,.12); }.goal-presets button, .feedback-row button { background: #fff; color: #53647d; border-color: rgba(20,33,58,.16); }.goal-presets strong { color: #14213a; }.goal-presets button:hover, .goal-presets button.active, .feedback-row button:hover, .feedback-row button.active { color: #176b4d; border-color: #8ed4ac; background: #effaf3; }.goal-presets button:hover strong, .goal-presets button.active strong { color: #176b4d; }.inline-error { background: #fff4f4; color: #b42318; border-left-color: #e5484d; }.video-evidence { background: #fff; border-color: rgba(20,33,58,.12); }.video-evidence-loading, .video-evidence p { color: #53647d; }
</style>
