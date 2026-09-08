#!/usr/bin/env bash
# =============================================================
# 停止管理平台(423)与 AI 识别服务(8000)
# MySQL 由 brew services 管理，默认不停
# 用法：./stop-all.sh
# 注意：423 若跑的是 IDEA 里启动的实例，同样会被停止
# =============================================================
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_DIR="$ROOT_DIR/logs"

stop_by_port() { # $1=端口 $2=名称
    local pid
    pid="$(lsof -nP -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null | head -1)"
    if [ -n "$pid" ]; then
        kill "$pid" && echo "   已停止 $2（端口 $1，PID ${pid}）"
    else
        echo "   $2（端口 $1）未在运行"
    fi
}

stop_by_pidfile() { # $1=pid文件 $2=名称（端口方式漏网时的兜底）
    [ -f "$1" ] || return
    local pid
    pid="$(cat "$1" 2>/dev/null)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        kill "$pid" && echo "   已停止 $2（PID ${pid}）"
    fi
    rm -f "$1"
}

echo "==> 停止 AI 识别服务(8000)"
stop_by_port 8000 "AI 服务"
echo "==> 停止管理平台(423)"
stop_by_port 423 "管理平台"
echo "==> PID 文件兜底清理"
stop_by_pidfile "$PID_DIR/platform.pid" "平台"
stop_by_pidfile "$PID_DIR/ai-service.pid" "AI 服务"

echo "==> 停止 Cloudflare 临时穿透"
stop_by_pidfile "$PID_DIR/cloudflared.pid" "穿透"
# 兜底：清理由其他方式启动的本机 quick tunnel
if pgrep -f "cloudflared tunnel --url http://localhost:423" >/dev/null 2>&1; then
    pkill -f "cloudflared tunnel --url http://localhost:423" && echo "   已清理残留穿透进程"
fi

echo "完成。MySQL 如需停止：brew services stop mysql@8.0"
