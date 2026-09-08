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
        const videoFile = ref(null)
        const analyzingVideo = ref(false)
        const videoResult = ref(null)
        const aiForm = ref({ imageUrl: '', area: 'A区西门', deviceCode: 'CAM-001' })
        let clockTimer = null
        let socket = null
        let reconnectTimer = null
        let toastTimer = null
        let highlightTimer = null

        const latestAlarms = computed(() => alarms.value.slice(0, 6))
        const aiAlarms = computed(() => alarms.value.filter(item => item.source === 'AI视觉分析'))
        const activeAlarms = computed(() => alarms.value.filter(item => item.status !== '已关闭'))
        const closedAlarms = computed(() => alarms.value.filter(item => item.status === '已关闭'))

        async function request(path, options = {}) {
            const response = await fetch(path, {
                headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
                ...options
            })
            if (!response.ok) {
                let message = `${path} 请求失败：HTTP ${response.status}`
                try {
                    const body = await response.json()
                    if (body.message) message = body.message
                } catch (_) {
                    // 使用默认错误信息。
                }
                throw new Error(message)
            }
            return response.json()
        }

        async function loadAll() {
            loading.value = true
            error.value = ''
            try {
                const [dashboardData, alarmData, deviceData] = await Promise.all([
                    request('/api/dashboard'), request('/api/alarms'), request('/api/devices')
                ])
                dashboard.value = dashboardData
                alarms.value = alarmData
                devices.value = deviceData
            } catch (exception) {
                error.value = `数据加载失败：${exception.message}`
            } finally {
                loading.value = false
            }
        }

        async function updateAlarm(id, status) {
            const path = status === '处置中' ? `/api/alarms/${id}/process` : `/api/alarms/${id}/handle`
            try {
                await request(path, { method: 'POST' })
                showToast(`告警已更新为“${status}”`)
                await loadAll()
            } catch (exception) {
                error.value = `告警更新失败：${exception.message}`
            }
        }

        async function analyzeImage() {
            analyzing.value = true
            error.value = ''
            lastAiResult.value = ''
            aiAnnotated.value = ''
            try {
                const result = await request('/api/ai/analyze', {
                    method: 'POST',
                    body: JSON.stringify(aiForm.value)
                })
                const objectText = result.objects.length
                    ? result.objects.map(item => `${item.class} ${(item.conf * 100).toFixed(1)}%`).join('、')
                    : '未识别到目标'
                lastAiResult.value = `识别结果：${objectText}；生成 ${result.alarms.length} 条业务告警。`
                aiAnnotated.value = result.annotated || ''
                showToast('AI 识别已完成')
                await loadAll()
            } catch (exception) {
                error.value = `AI识别失败：${exception.message}`
            } finally {
                analyzing.value = false
            }
        }

        async function analyzeVideo() {
            if (!videoFile.value) {
                error.value = '请先选择视频文件'
                return
            }
            analyzingVideo.value = true
            error.value = ''
            videoResult.value = null
            try {
                const formData = new FormData()
                formData.append('file', videoFile.value)
                formData.append('area', aiForm.value.area || '')
                formData.append('deviceCode', aiForm.value.deviceCode || '')
                const response = await fetch('/api/ai/analyze-video', { method: 'POST', body: formData })
                if (!response.ok) {
                    let message = `视频识别失败：HTTP ${response.status}`
                    try {
                        const body = await response.json()
                        if (body.message) message = body.message
                    } catch (_) {
                        // 使用默认错误信息。
                    }
                    throw new Error(message)
                }
                videoResult.value = await response.json()
                showToast('视频识别已完成')
                await loadAll()
            } catch (exception) {
                error.value = exception.message
            } finally {
                analyzingVideo.value = false
            }
        }

        function connectSocket() {
            clearTimeout(reconnectTimer)
            const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
            socket = new WebSocket(`${protocol}//${location.host}/ws/alerts`)
            socket.onopen = () => { socketConnected.value = true }
            socket.onmessage = async event => {
                try {
                    const message = JSON.parse(event.data)
                    const alarm = message.alarm || {}
                    if (message.event === 'new-alarm') {
                        showToast(`收到新告警：${alarm.type || '未知类型'}（${alarm.area || '未知区域'}）`, alarm.id)
                    } else {
                        showToast(`告警状态已更新：${alarm.status || ''}`)
                    }
                    await loadAll()
                } catch (_) {
                    await loadAll()
                }
            }
            socket.onerror = () => { socketConnected.value = false }
            socket.onclose = () => {
                socketConnected.value = false
                reconnectTimer = setTimeout(connectSocket, 3000)
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
            // 跳转到实时告警中心并高亮对应告警行 4 秒
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
            // eventTime 是带时区的 ISO 字符串(UTC)，sentAt 是无时区的本地时间，
            // 统一用 Date 解析后按本机时区格式化，避免差 8 小时
            const date = new Date(value)
            if (isNaN(date)) return value.replace('T', ' ').slice(0, 19)
            const pad = n => String(n).padStart(2, '0')
            return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
                + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
        }

        function levelClass(level) {
            return level === '高' ? 'high' : level === '中' ? 'medium' : 'low'
        }

        function deviceAbbr(type) {
            return type === '摄像头' ? 'CAM' : type === '门禁' ? 'ACS' : type === '烟感' ? 'SMK' : 'DEV'
        }

        onMounted(() => {
            updateClock()
            clockTimer = setInterval(updateClock, 1000)
            loadAll()
            connectSocket()
        })

        onBeforeUnmount(() => {
            clearInterval(clockTimer)
            clearTimeout(reconnectTimer)
            clearTimeout(toastTimer)
            clearTimeout(highlightTimer)
            if (socket) socket.close()
        })

        return {
            navItems, activePage, sidebarOpen, nowText, dashboard, alarms, devices, loading, analyzing,
            socketConnected, error, toast, toastAlarmId, highlightAlarmId, lastAiResult, aiAnnotated,
            videoFile, analyzingVideo, videoResult, aiForm, latestAlarms, aiAlarms, activeAlarms,
            closedAlarms, loadAll, updateAlarm, analyzeImage, analyzeVideo, selectPage, goToAlarm,
            formatTime, levelClass, deviceAbbr
        }
    }
}).mount('#app')
