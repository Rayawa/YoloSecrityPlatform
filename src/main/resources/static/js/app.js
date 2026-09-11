const { createApp, ref, computed, onMounted, onBeforeUnmount } = Vue

createApp({
    setup() {
        const navItems = [
            { id: 'dashboard', label: '监控大屏', icon: '▦' },
            { id: 'devices', label: '设备信息', icon: '▣' },
            { id: 'ai', label: 'AI识别记录', icon: '◎' },
            { id: 'alerts', label: '实时告警中心', icon: '!' },
            { id: 'history', label: '告警处置记录', icon: '✓' }
        ]
        const emptyFilter = () => ({ type: '', area: '', level: '', status: '' })
        const activePage = ref('dashboard')
        const sidebarOpen = ref(false)
        const nowText = ref('')
        const dashboard = ref({ onlineDevices: 0, deviceCount: 0, alarmCount: 0, pendingAlarms: 0, aiEvents: 0 })
        const alarms = ref([])
        const devices = ref([])
        const loading = ref(false)
        const analyzing = ref(false)
        const socketConnected = ref(false)
        const error = ref('')
        const toast = ref('')
        const toastAlarmId = ref(null)
        const highlightAlarmId = ref(null)
        const lastAiResult = ref('')
        const aiAnnotated = ref('')
        const aiFile = ref(null)
        const videoResult = ref(null)
        const aiForm = ref({ area: 'A区西门', deviceCode: 'CAM-001' })
        const authReady = ref(false)
        const authenticated = ref(false)
        const currentUser = ref('')
        const loggingIn = ref(false)
        const loginError = ref('')
        const loginForm = ref({ username: '', password: '' })
        const filters = ref({ ai: emptyFilter(), alerts: emptyFilter(), history: emptyFilter() })
        const sorts = ref({
            ai: { key: 'eventTime', direction: 'desc' },
            alerts: { key: 'eventTime', direction: 'desc' },
            history: { key: 'eventTime', direction: 'desc' }
        })
        let clockTimer = null
        let socket = null
        let reconnectTimer = null
        let reconnectAttempts = 0
        let toastTimer = null
        let highlightTimer = null
        let loadAllPromise = null
        let refreshAlarmPromise = null

        const uniqueSorted = values => [...new Set(values.filter(Boolean))].sort((a, b) => a.localeCompare(b, 'zh-CN'))
        const filterOptions = computed(() => ({
            types: uniqueSorted(alarms.value.map(item => item.type)),
            areas: uniqueSorted(alarms.value.map(item => item.area)),
            levels: ['高', '中', '低'],
            statuses: ['待处置', '处置中', '已关闭']
        }))
        const latestAlarms = computed(() => alarms.value.slice(0, 6))
        const aiAlarms = computed(() => filterAndSort('ai'))
        const activeAlarms = computed(() => filterAndSort('alerts'))
        const closedAlarms = computed(() => filterAndSort('history'))

        function resetAuthentication() {
            authenticated.value = false
            currentUser.value = ''
            socketConnected.value = false
            clearTimeout(reconnectTimer)
            if (socket) {
                socket.onclose = null
                socket.close()
                socket = null
            }
        }

        async function parseResponse(response, path) {
            if (!response.ok) {
                let message = `${path} 请求失败：HTTP ${response.status}`
                try {
                    const body = await response.json()
                    if (body.message || body.detail) message = body.message || body.detail
                } catch (_) {
                    // 使用默认错误信息。
                }
                if (response.status === 401 && path !== '/api/auth/login') resetAuthentication()
                throw new Error(message)
            }
            return response.json()
        }

        async function request(path, options = {}) {
            const headers = { ...(options.headers || {}) }
            if (options.body && !(options.body instanceof FormData) && !headers['Content-Type']) {
                headers['Content-Type'] = 'application/json'
            }
            const response = await fetch(path, { ...options, headers })
            return parseResponse(response, path)
        }

        async function checkSession() {
            try {
                const session = await request('/api/auth/session')
                authenticated.value = session.authenticated
                currentUser.value = session.username || ''
            } catch (_) {
                resetAuthentication()
            } finally {
                authReady.value = true
            }
        }

        async function login() {
            loggingIn.value = true
            loginError.value = ''
            try {
                const session = await request('/api/auth/login', {
                    method: 'POST',
                    body: JSON.stringify(loginForm.value)
                })
                authenticated.value = true
                currentUser.value = session.username
                loginForm.value.password = ''
                await loadAll()
                connectSocket()
            } catch (exception) {
                loginError.value = exception.message
            } finally {
                loggingIn.value = false
            }
        }

        async function logout() {
            try {
                await request('/api/auth/logout', { method: 'POST' })
            } finally {
                resetAuthentication()
                alarms.value = []
                devices.value = []
                activePage.value = 'dashboard'
            }
        }

        async function loadAll() {
            if (!authenticated.value) return
            if (loadAllPromise) return loadAllPromise
            loading.value = true
            error.value = ''
            loadAllPromise = Promise.all([
                request('/api/dashboard'), request('/api/alarms'), request('/api/devices')
            ]).then(([dashboardData, alarmData, deviceData]) => {
                dashboard.value = dashboardData
                alarms.value = alarmData
                devices.value = deviceData
            }).catch(exception => {
                if (authenticated.value) error.value = `数据加载失败：${exception.message}`
            }).finally(() => {
                loading.value = false
                loadAllPromise = null
            })
            return loadAllPromise
        }

        async function refreshAlarmData() {
            if (!authenticated.value) return
            if (refreshAlarmPromise) return refreshAlarmPromise
            refreshAlarmPromise = Promise.all([
                request('/api/dashboard'), request('/api/alarms')
            ]).then(([dashboardData, alarmData]) => {
                dashboard.value = dashboardData
                alarms.value = alarmData
            }).catch(exception => {
                if (authenticated.value) error.value = `告警刷新失败：${exception.message}`
            }).finally(() => { refreshAlarmPromise = null })
            return refreshAlarmPromise
        }

        async function updateAlarm(id, status) {
            const path = status === '处置中' ? `/api/alarms/${id}/process` : `/api/alarms/${id}/handle`
            try {
                await request(path, { method: 'POST' })
                showToast(`告警已更新为“${status}”`)
                await refreshAlarmData()
            } catch (exception) {
                if (authenticated.value) error.value = `告警更新失败：${exception.message}`
            }
        }

        function selectAiFile(event) {
            aiFile.value = event.target.files?.[0] || null
            lastAiResult.value = ''
            aiAnnotated.value = ''
            videoResult.value = null
        }

        async function analyzeFile() {
            if (!aiFile.value) {
                error.value = '请先选择图片或视频文件'
                return
            }
            const filename = aiFile.value.name.toLowerCase()
            const isVideo = /\.(mp4|avi|mov)$/.test(filename) || aiFile.value.type.startsWith('video/')
            const isImage = /\.(jpg|jpeg|png)$/.test(filename) || aiFile.value.type.startsWith('image/')
            if (!isVideo && !isImage) {
                error.value = '仅支持 JPG、PNG、MP4、AVI、MOV 文件'
                return
            }

            analyzing.value = true
            error.value = ''
            lastAiResult.value = ''
            aiAnnotated.value = ''
            videoResult.value = null
            try {
                const formData = new FormData()
                formData.append('file', aiFile.value)
                formData.append('area', aiForm.value.area || '')
                formData.append('deviceCode', aiForm.value.deviceCode || '')
                const path = isVideo ? '/api/ai/analyze-video' : '/api/ai/analyze-image'
                const result = await request(path, { method: 'POST', body: formData })
                if (isVideo) {
                    videoResult.value = result
                    const skippedText = result.skipped.length ? `，跳过 ${result.skipped.join('、')}` : ''
                    lastAiResult.value = `视频抽帧识别完成：生成 ${result.alarms.length} 条业务告警${skippedText}。`
                } else {
                    const objectText = result.objects.length
                        ? result.objects.map(item => `${item.class} ${(item.conf * 100).toFixed(1)}%`).join('、')
                        : '未识别到目标'
                    lastAiResult.value = `图片识别结果：${objectText}；生成 ${result.alarms.length} 条业务告警。`
                    aiAnnotated.value = result.annotated || ''
                }
                showToast('AI 识别已完成')
                await refreshAlarmData()
            } catch (exception) {
                if (authenticated.value) error.value = `AI识别失败：${exception.message}`
            } finally {
                analyzing.value = false
            }
        }

        function scopedAlarms(page) {
            if (page === 'ai') return alarms.value.filter(item => item.source === 'AI视觉分析')
            if (page === 'alerts') return alarms.value.filter(item => item.status !== '已关闭')
            return alarms.value.filter(item => item.status === '已关闭')
        }

        function filterAndSort(page) {
            const filter = filters.value[page]
            const sort = sorts.value[page]
            const levelOrder = { 高: 3, 中: 2, 低: 1 }
            const statusOrder = { 待处置: 1, 处置中: 2, 已关闭: 3 }
            const result = scopedAlarms(page).filter(item =>
                (!filter.type || item.type === filter.type)
                && (!filter.area || item.area === filter.area)
                && (!filter.level || item.level === filter.level)
                && (!filter.status || item.status === filter.status)
            )
            return result.sort((left, right) => {
                let comparison
                if (sort.key === 'eventTime') {
                    comparison = new Date(left.eventTime).getTime() - new Date(right.eventTime).getTime()
                } else if (sort.key === 'level') {
                    comparison = (levelOrder[left.level] || 0) - (levelOrder[right.level] || 0)
                } else if (sort.key === 'status') {
                    comparison = (statusOrder[left.status] || 0) - (statusOrder[right.status] || 0)
                } else {
                    comparison = String(left[sort.key] || '').localeCompare(String(right[sort.key] || ''), 'zh-CN')
                }
                return sort.direction === 'asc' ? comparison : -comparison
            })
        }

        function toggleSort(page, key) {
            const current = sorts.value[page]
            if (current.key === key) {
                current.direction = current.direction === 'asc' ? 'desc' : 'asc'
            } else {
                current.key = key
                current.direction = key === 'eventTime' ? 'desc' : 'asc'
            }
        }

        function sortMark(page, key) {
            const sort = sorts.value[page]
            return sort.key === key ? (sort.direction === 'asc' ? '↑' : '↓') : '↕'
        }

        function ariaSort(page, key) {
            const sort = sorts.value[page]
            if (sort.key !== key) return 'none'
            return sort.direction === 'asc' ? 'ascending' : 'descending'
        }

        function clearFilters(page) {
            filters.value[page] = emptyFilter()
        }

        function connectSocket() {
            clearTimeout(reconnectTimer)
            if (!authenticated.value || (socket && socket.readyState <= WebSocket.OPEN)) return
            const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
            socket = new WebSocket(`${protocol}//${location.host}/ws/alerts`)
            socket.onopen = () => {
                socketConnected.value = true
                reconnectAttempts = 0
            }
            socket.onmessage = async event => {
                try {
                    const message = JSON.parse(event.data)
                    const alarm = message.alarm || {}
                    if (message.event === 'new-alarm') {
                        showToast(`收到新告警：${alarm.type || '未知类型'}（${alarm.area || '未知区域'}）`, alarm.id)
                    } else {
                        showToast(`告警状态已更新：${alarm.status || ''}`)
                    }
                } catch (_) {
                    // 收到非预期消息时仍刷新数据，避免实时列表失步。
                } finally {
                    await refreshAlarmData()
                }
            }
            socket.onerror = () => { socketConnected.value = false }
            socket.onclose = () => {
                socketConnected.value = false
                socket = null
                if (!authenticated.value) return
                const delay = Math.min(3000 * (2 ** reconnectAttempts), 30000)
                reconnectAttempts += 1
                reconnectTimer = setTimeout(connectSocket, delay)
            }
        }

        function selectPage(page) {
            activePage.value = page
            sidebarOpen.value = false
            error.value = ''
        }

        function showToast(message, alarmId = null) {
            toast.value = message
            toastAlarmId.value = alarmId
            clearTimeout(toastTimer)
            toastTimer = setTimeout(() => { toast.value = ''; toastAlarmId.value = null }, 4000)
        }

        function goToAlarm() {
            if (!toastAlarmId.value) return
            highlightAlarmId.value = toastAlarmId.value
            selectPage('alerts')
            toast.value = ''
            toastAlarmId.value = null
            clearTimeout(highlightTimer)
            highlightTimer = setTimeout(() => { highlightAlarmId.value = null }, 4000)
        }

        function updateClock() {
            nowText.value = new Date().toLocaleString('zh-CN', {
                year: 'numeric', month: '2-digit', day: '2-digit',
                hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
            }).replaceAll('/', '-')
        }

        function formatTime(value) {
            if (!value) return '—'
            const date = new Date(value)
            if (isNaN(date)) return value.replace('T', ' ').slice(0, 19)
            const pad = number => String(number).padStart(2, '0')
            return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
        }

        function levelClass(level) {
            return level === '高' ? 'high' : level === '中' ? 'medium' : 'low'
        }

        function deviceAbbr(type) {
            return type === '摄像头' ? 'CAM' : type === '门禁' ? 'ACS' : type === '烟感' ? 'SMK' : 'DEV'
        }

        onMounted(async () => {
            updateClock()
            clockTimer = setInterval(updateClock, 1000)
            await checkSession()
            if (authenticated.value) {
                await loadAll()
                connectSocket()
            }
        })

        onBeforeUnmount(() => {
            clearInterval(clockTimer)
            clearTimeout(reconnectTimer)
            clearTimeout(toastTimer)
            clearTimeout(highlightTimer)
            if (socket) socket.close()
        })

        return {
            navItems, activePage, sidebarOpen, nowText, dashboard, devices, loading, analyzing,
            socketConnected, error, toast, toastAlarmId, highlightAlarmId, lastAiResult, aiAnnotated,
            videoResult, aiForm, authReady, authenticated, currentUser, loggingIn, loginError, loginForm,
            filters, filterOptions, latestAlarms, aiAlarms, activeAlarms, closedAlarms, loadAll, login, logout,
            updateAlarm, selectAiFile, analyzeFile, toggleSort, sortMark, ariaSort, clearFilters, selectPage,
            goToAlarm, formatTime, levelClass, deviceAbbr
        }
    }
}).mount('#app')
