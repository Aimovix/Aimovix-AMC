#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# Local LLM manager for Termux (llama.cpp / llama-server)
#
# Non-interactive, so the AMC bridge can drive it (stdin is /dev/null there):
#   local_model_manager.sh status
#   local_model_manager.sh install --model NAME.gguf --url https://... [--sha256 HEX]
#   local_model_manager.sh start   --model NAME.gguf [--ctx 8192] [--threads N] [--port 8080]
#   local_model_manager.sh stop
#
# Without arguments in a terminal it asks interactively (catalog from model_catalog.json).
#
# Every line starting with "status." is machine readable (KEY=VALUE) for the bridge.
# ==============================================================================

set -u
set -o pipefail

AGENT_DIR="$HOME/.termux_agent"
MODELS_DIR="$AGENT_DIR/models"
RUN_DIR="$AGENT_DIR/llm"
PID_FILE="$RUN_DIR/llama-server.pid"
LOG_FILE="$RUN_DIR/llama-server.log"
CONF_FILE="$RUN_DIR/server.conf"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CATALOG="$SCRIPT_DIR/model_catalog.json"
mkdir -p "$MODELS_DIR" "$RUN_DIR"

DEFAULT_PORT=8080
DEFAULT_CTX=8192
MIN_GGUF_BYTES=104857600

log() { echo "$*" >&2; }
die() { log "❌ $*"; echo "status.error=$*"; exit 1; }

# ------------------------------------------------------------------------------
# Arguments
# ------------------------------------------------------------------------------
CMD="${1:-}"
[ $# -gt 0 ] && shift
MODEL=""; URL=""; SHA256=""; CTX="$DEFAULT_CTX"; THREADS=""; PORT="$DEFAULT_PORT"
while [ $# -gt 0 ]; do
    case "$1" in
        --model) MODEL="${2:-}"; shift 2 ;;
        --url) URL="${2:-}"; shift 2 ;;
        --sha256) SHA256="${2:-}"; shift 2 ;;
        --ctx) CTX="${2:-}"; shift 2 ;;
        --threads) THREADS="${2:-}"; shift 2 ;;
        --port) PORT="${2:-}"; shift 2 ;;
        *) die "Unknown argument: $1" ;;
    esac
done

case "$MODEL" in
    ""|*/*|.*) [ -z "$MODEL" ] || die "Invalid model name." ;;
esac
[[ "$CTX" =~ ^[0-9]+$ ]] || die "ctx must be a number."
[[ "$PORT" =~ ^[0-9]+$ ]] || die "port must be a number."
[ -z "$THREADS" ] || [[ "$THREADS" =~ ^[0-9]+$ ]] || die "threads must be a number."

# ------------------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------------------

# Number of performance cores: CPUs whose max frequency is above the lowest cluster's.
perf_cores() {
    local freqs min count=0 f
    freqs=$(cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq 2>/dev/null || true)
    if [ -z "$freqs" ]; then
        nproc 2>/dev/null || echo 4
        return
    fi
    min=$(echo "$freqs" | sort -n | head -1)
    for f in $freqs; do
        [ "$f" -gt "$min" ] && count=$((count + 1))
    done
    [ "$count" -gt 0 ] || count=$(echo "$freqs" | wc -l)
    echo "$count"
}

is_valid_gguf() {
    local file="$1" size magic
    [ -f "$file" ] || return 1
    size=$(stat -c %s "$file" 2>/dev/null || wc -c < "$file")
    [ "$size" -ge "$MIN_GGUF_BYTES" ] || return 1
    magic=$(head -c 4 "$file" 2>/dev/null || true)
    [ "$magic" = "GGUF" ]
}

server_pid() {
    local pid
    [ -f "$PID_FILE" ] || return 1
    pid=$(cat "$PID_FILE" 2>/dev/null)
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && echo "$pid"
}

healthy() {
    curl -fsS --max-time 2 "http://127.0.0.1:$1/health" >/dev/null 2>&1
}

ensure_llama() {
    command -v llama-server >/dev/null 2>&1 && return 0
    log "📦 Installing llama.cpp (Termux package llama-cpp)..."
    pkg install -y llama-cpp >&2 || apt-get install -y llama-cpp >&2 || return 1
    command -v llama-server >/dev/null 2>&1
}

# Adds a flag only when this llama-server build knows it (flags changed between releases).
supports() {
    llama-server --help 2>&1 | grep -q -- "$1"
}

# ------------------------------------------------------------------------------
# Commands
# ------------------------------------------------------------------------------

cmd_status() {
    local pid port=""
    echo "status.llama_installed=$(command -v llama-server >/dev/null 2>&1 && echo 1 || echo 0)"
    echo "status.models=$(cd "$MODELS_DIR" && ls -1 *.gguf 2>/dev/null | tr '\n' ',' | sed 's/,$//')"
    echo "status.perf_cores=$(perf_cores)"
    if pid=$(server_pid); then
        [ -f "$CONF_FILE" ] && . "$CONF_FILE"
        port="${SERVER_PORT:-$DEFAULT_PORT}"
        echo "status.running=1"
        echo "status.pid=$pid"
        echo "status.port=$port"
        echo "status.model=${SERVER_MODEL:-}"
        echo "status.healthy=$(healthy "$port" && echo 1 || echo 0)"
    else
        echo "status.running=0"
    fi
}

cmd_install() {
    [ -n "$MODEL" ] || die "--model is required."
    ensure_llama || die "llama.cpp could not be installed (pkg install llama-cpp)."
    local path="$MODELS_DIR/$MODEL" part="$MODELS_DIR/$MODEL.part"
    if is_valid_gguf "$path"; then
        if [ -z "$SHA256" ] || echo "$SHA256  $path" | sha256sum -c --status 2>/dev/null; then
            log "✅ Model already present: $MODEL"
            echo "status.installed=$MODEL"
            return 0
        fi
        log "⚠️ Existing file does not match the checksum; downloading again."
        rm -f "$path"
    fi
    [ -n "$URL" ] || die "--url is required to download $MODEL."
    case "$URL" in https://*) ;; *) die "Only https URLs are allowed." ;; esac
    log "⬇️ Downloading $MODEL (resumes a partial download)..."
    # -C - resumes the .part file after an interruption.
    curl --fail --show-error --location --retry 5 --retry-delay 5 -C - "$URL" -o "$part" >&2 \
        || die "Download failed (the partial file is kept and resumed next time)."
    is_valid_gguf "$part" || { rm -f "$part"; die "The download is not a GGUF model."; }
    if [ -n "$SHA256" ]; then
        echo "$SHA256  $part" | sha256sum -c --status || { rm -f "$part"; die "SHA-256 mismatch."; }
    else
        log "⚠️ No SHA-256 given; only the GGUF header was checked."
    fi
    mv -f "$part" "$path"
    log "✅ Installed $MODEL"
    echo "status.installed=$MODEL"
}

cmd_stop() {
    local pid
    if pid=$(server_pid); then
        kill "$pid" 2>/dev/null
        for _ in 1 2 3 4 5 6 7 8 9 10; do
            kill -0 "$pid" 2>/dev/null || break
            sleep 0.5
        done
        kill -9 "$pid" 2>/dev/null || true
        log "🛑 Stopped llama-server ($pid)."
    fi
    rm -f "$PID_FILE"
    echo "status.running=0"
}

cmd_start() {
    [ -n "$MODEL" ] || die "--model is required."
    local path="$MODELS_DIR/$MODEL"
    is_valid_gguf "$path" || die "Model $MODEL is not installed. Run install first."
    command -v llama-server >/dev/null 2>&1 || die "llama-server is not installed."
    server_pid >/dev/null && cmd_stop >/dev/null
    local threads="${THREADS:-$(perf_cores)}"
    local args=(-m "$path" --host 127.0.0.1 --port "$PORT" -c "$CTX" --threads "$threads")
    # Tool calling: the chat template turns tool calls into OpenAI tool_calls.
    supports "--jinja" && args+=(--jinja)
    # Smaller KV cache and faster attention on phones.
    supports "--cache-type-k" && args+=(--cache-type-k q8_0 --cache-type-v q8_0)
    if supports "--flash-attn"; then
        if llama-server --help 2>&1 | grep -q -- "--flash-attn.*\(on\|auto\)"; then args+=(--flash-attn on); else args+=(--flash-attn); fi
    fi
    # Reuse the KV cache across requests with the same prefix (system prompt + tools).
    supports "--cache-reuse" && args+=(--cache-reuse 256)
    # Thinking off: hybrid models (Qwen3.x) otherwise spend the budget on reasoning tokens.
    supports "--reasoning-budget" && args+=(--reasoning-budget 0)
    supports "--no-mmproj" && args+=(--no-mmproj)
    log "🚀 Starting llama-server on 127.0.0.1:$PORT (ctx $CTX, $threads threads)..."
    # setsid + nohup: the server survives the bridge's command timeout and the terminal.
    setsid nohup llama-server "${args[@]}" >"$LOG_FILE" 2>&1 < /dev/null &
    echo $! > "$PID_FILE"
    printf 'SERVER_PORT=%s\nSERVER_MODEL=%s\n' "$PORT" "$MODEL" > "$CONF_FILE"
    for _ in $(seq 1 120); do
        if healthy "$PORT"; then
            log "✅ llama-server is ready: http://127.0.0.1:$PORT/v1"
            echo "status.running=1"
            echo "status.port=$PORT"
            echo "status.model=$MODEL"
            return 0
        fi
        server_pid >/dev/null || { tail -20 "$LOG_FILE" >&2; die "llama-server exited during startup."; }
        sleep 1
    done
    tail -20 "$LOG_FILE" >&2
    die "llama-server did not become healthy within 120 s (see $LOG_FILE)."
}

cmd_interactive() {
    [ -t 0 ] || die "No command given (status|install|start|stop)."
    ensure_llama || die "llama.cpp could not be installed."
    local entries=()
    if [ -f "$CATALOG" ] && command -v python3 >/dev/null 2>&1; then
        mapfile -t entries < <(python3 -c '
import json, sys
for m in json.load(open(sys.argv[1]))["models"]:
    print("\t".join([m["file"], m["url"], m.get("sha256", ""), str(m.get("ctx", 8192)), m["title"]]))
' "$CATALOG")
    fi
    [ ${#entries[@]} -gt 0 ] || die "No model catalog found ($CATALOG)."
    local i=1 e
    for e in "${entries[@]}"; do
        echo "$i) $(echo "$e" | cut -f5)"
        i=$((i + 1))
    done
    local choice
    read -r -p "Selection [1-${#entries[@]}, default 1]: " choice || choice=1
    choice=${choice:-1}
    [[ "$choice" =~ ^[0-9]+$ ]] && [ "$choice" -ge 1 ] && [ "$choice" -le ${#entries[@]} ] || choice=1
    e="${entries[$((choice - 1))]}"
    MODEL=$(echo "$e" | cut -f1); URL=$(echo "$e" | cut -f2); SHA256=$(echo "$e" | cut -f3); CTX=$(echo "$e" | cut -f4)
    cmd_install && cmd_start
    echo "In AMC Settings select 'Local' with http://127.0.0.1:$PORT/v1"
}

case "$CMD" in
    status) cmd_status ;;
    install) cmd_install ;;
    start) cmd_start ;;
    stop) cmd_stop ;;
    "") cmd_interactive ;;
    *) die "Unknown command '$CMD' (status|install|start|stop)." ;;
esac
