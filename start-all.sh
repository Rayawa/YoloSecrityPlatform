#!/usr/bin/env bash
# =============================================================
# 一键启动：MySQL(3306) + 管理平台(423) + AI 识别服务(8000) + Cloudflare 临时穿透
# 用法：./start-all.sh      停止：./stop-all.sh
# 日志：logs/platform.log、logs/ai-service.log、logs/cloudflared.log
# 说明：端口已被占用时自动跳过启动（如 IDEA 里已在运行的平台）；
#       已安装 cloudflared 时自动开启临时公网穿透（地址每次启动会变）。
# =============================================================
set -u
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$ROOT_DIR/logs"
mkdir -p "$LOG_DIR"

# ---- 探测 Java（优先 JAVA_HOME，其次 Homebrew openjdk@17）----
JAVA_BIN=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
elif [ -x "/opt/homebrew/opt/openjdk@17/bin/java" ]; then
    JAVA_BIN="/opt/homebrew/opt/openjdk@17/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_BIN="$(command -v java)"
else
    echo "[错误] 找不到 Java 17，请设置 JAVA_HOME 或安装 openjdk@17"
    exit 1
fi

port_in_use() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

wait_ready() { # $1=探测URL $2=名称 $3=超时秒
    local i=0
    until curl -s -o /dev/null --max-time 1 "$1"; do
        i=$((i + 1))
        if [ "$i" -ge "$3" ]; then return 1; fi
        sleep 1
    done
}

# ---- 1. MySQL ----
echo "==> [1/4] 检查 MySQL(3306)"
if port_in_use 3306; then
    echo "    MySQL 已在运行"
else
    echo "    MySQL 未运行，尝试 brew services start mysql@8.0 ..."
    brew services start mysql@8.0 >/dev/null 2>&1 \
        || { echo "[错误] 启动 MySQL 失败，请手动启动"; exit 1; }
    sleep 3
fi
mysql -h127.0.0.1 -P3306 -uroot -proot -e "SELECT 1" >/dev/null 2>&1 \
    || { echo "[错误] MySQL 连接失败（root/root），请检查账号或先导入 sql/mysql8-security-monitor.sql"; exit 1; }
echo "    MySQL 就绪"

# ---- 2. 管理平台 ----
echo "==> [2/4] 启动管理平台(423)"
if port_in_use 423; then
    echo "    423 已被占用（IDEA 或之前启动的实例），跳过启动，直接复用"
else
    JAR="$ROOT_DIR/target/security-monitor-1.0.0.jar"
    if [ ! -f "$JAR" ] || [ -n "$(find "$ROOT_DIR/src" -name '*.java' -newer "$JAR" 2>/dev/null | head -1)" ]; then
        echo "    jar 缺失或源码有更新，先 mvn clean package -DskipTests ..."
        (cd "$ROOT_DIR" && mvn clean package -DskipTests -q) || { echo "[错误] 构建失败"; exit 1; }
    fi
    nohup "$JAVA_BIN" -jar "$JAR" > "$LOG_DIR/platform.log" 2>&1 &
    echo $! > "$LOG_DIR/platform.pid"
fi
wait_ready "http://localhost:423/api/dashboard" "管理平台" 60 \
    || { echo "[错误] 平台 60 秒内未就绪，查看 logs/platform.log"; exit 1; }
echo "    管理平台就绪：http://localhost:423"

# ---- 3. AI 识别服务 ----
echo "==> [3/4] 启动 AI 识别服务(8000)"
if port_in_use 8000; then
    echo "    8000 已被占用，跳过启动"
else
    PYTHON_BIN="$ROOT_DIR/ai-service/.venv/bin/python"
    [ -x "$PYTHON_BIN" ] || PYTHON_BIN="$(command -v python3 2>/dev/null || true)"
    [ -n "$PYTHON_BIN" ] || { echo "[错误] 找不到 python3，请先在 ai-service 创建 .venv（见 README）"; exit 1; }
    nohup "$PYTHON_BIN" "$ROOT_DIR/ai-service/server.py" > "$LOG_DIR/ai-service.log" 2>&1 &
    echo $! > "$LOG_DIR/ai-service.pid"
fi
wait_ready "http://127.0.0.1:8000/health" "AI 服务" 120 \
    || { echo "[错误] AI 服务 120 秒内未就绪（首次需加载模型），查看 logs/ai-service.log"; exit 1; }
echo "    AI 服务就绪：http://127.0.0.1:8000"

# ---- 4. Cloudflare 临时穿透（已安装时自动开启）----
echo "==> [4/4] 启动 Cloudflare 临时穿透"
tunnel_url=""
if command -v cloudflared >/dev/null 2>&1; then
    if [ -f "$LOG_DIR/cloudflared.pid" ] && kill -0 "$(cat "$LOG_DIR/cloudflared.pid")" 2>/dev/null; then
        echo "    穿透已在运行，跳过启动"
        tunnel_url="$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' "$LOG_DIR/cloudflared.log" 2>/dev/null | head -1)"
    else
        nohup cloudflared tunnel --url http://localhost:423 > "$LOG_DIR/cloudflared.log" 2>&1 &
        echo $! > "$LOG_DIR/cloudflared.pid"
        # 等待公网地址出现（首次连接约 3~10 秒）
        for _ in $(seq 1 20); do
            tunnel_url="$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' "$LOG_DIR/cloudflared.log" 2>/dev/null | head -1)"
            if [ -n "$tunnel_url" ]; then break; fi
            sleep 1
        done
        if [ -n "$tunnel_url" ]; then
            echo "    穿透已就绪"
        else
            echo "    穿透启动中（20 秒后仍无地址请查看 logs/cloudflared.log）"
        fi
    fi
else
    echo "    未安装 cloudflared，跳过（brew install cloudflared 后即可自动开启）"
fi

lan_ip="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}')"
echo
echo "=============================================="
echo " 全部就绪："
echo "   本机访问   http://localhost:423"
if [ -n "$lan_ip" ]; then
    echo "   局域网访问 http://${lan_ip}:423（手机/其他电脑同一 Wi-Fi 下）"
fi
if [ -n "$tunnel_url" ]; then
    echo "   公网穿透   ${tunnel_url}（临时地址，每次启动都会变）"
fi
echo "   AI 服务    http://127.0.0.1:8000/health"
echo "   投图目录   ai-service/ai-watch/（自动识别并归档到 processed/）"
echo "   日志       logs/platform.log、logs/ai-service.log、logs/cloudflared.log"
echo " 停止服务：  ./stop-all.sh"
echo "=============================================="
