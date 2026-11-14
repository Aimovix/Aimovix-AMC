#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# Local LLM manager for Termux (llama.cpp / llama-server)
#
# Non-interactive, so the AMC bridge can drive it (stdin is /dev/null there):
#   local_model_manager.sh status
#   local_model_manager.sh install --model NAME.gguf --url https://... [--sha256 HEX] [--size BYTES]
#       Starts the download in the background and returns at once; "status" reports progress.
#   local_model_manager.sh start   --model NAME.gguf [--ctx 8192] [--threads N] [--port 8080]
#   local_model_manager.sh stop
#   local_model_manager.sh cancel                  (stops a running download; the .part file is kept)
#   local_model_manager.sh delete  --model NAME.gguf
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
# Background download: its pid, its state (KEY=VALUE, rewritten atomically) and its log.
DL_PID_FILE="$RUN_DIR/download.pid"
DL_STATE_FILE="$RUN_DIR/install.state"
DL_LOG_FILE="$RUN_DIR/download.log"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CATALOG="$SCRIPT_DIR/model_catalog.json"
mkdir -p "$MODELS_DIR" "$RUN_DIR"

DEFAULT_PORT=8080
DEFAULT_CTX=8192
MIN_GGUF_BYTES="${AMC_MIN_GGUF_BYTES:-104857600}"

log() { echo "$*" >&2; }
die() { log "❌ $*"; echo "status.error=$*"; exit 1; }

# ------------------------------------------------------------------------------
# Arguments
# ------------------------------------------------------------------------------
CMD="${1:-}"
[ $# -gt 0 ] && shift
MODEL=""; URL=""; SHA256=""; CTX="$DEFAULT_CTX"; THREADS=""; PORT="$DEFAULT_PORT"; SIZE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --model) MODEL="${2:-}"; shift 2 ;;
        --url) URL="${2:-}"; shift 2 ;;
        --sha256) SHA256="${2:-}"; shift 2 ;;
        --ctx) CTX="${2:-}"; shift 2 ;;
        --threads) THREADS="${2:-}"; shift 2 ;;
        --port) PORT="${2:-}"; shift 2 ;;
        --size) SIZE="${2:-}"; shift 2 ;;
        *) die "Unknown argument: $1" ;;
    esac
done

case "$MODEL" in
    ""|*/*|.*) [ -z "$MODEL" ] || die "Invalid model name." ;;
esac
[[ "$CTX" =~ ^[0-9]+$ ]] || die "ctx must be a number."
[[ "$PORT" =~ ^[0-9]+$ ]] || die "port must be a number."
[ -z "$THREADS" ] || [[ "$THREADS" =~ ^[0-9]+$ ]] || die "threads must be a number."
[ -z "$SIZE" ] || [[ "$SIZE" =~ ^[0-9]+$ ]] || die "size must be a number."

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
    echo "status.free_mb=$(df -Pk "$MODELS_DIR" 2>/dev/null | awk 'NR==2 {print int($4/1024)}')"
    cmd_install_status
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

file_size() { stat -c %s "$1" 2>/dev/null || echo 0; }

download_pid() {
    local pid
    [ -f "$DL_PID_FILE" ] || return 1
    pid=$(cat "$DL_PID_FILE" 2>/dev/null)
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && echo "$pid"
}

# Writes the download state atomically: phase model total error.
write_state() {
    local tmp="$DL_STATE_FILE.tmp"
    printf 'PHASE=%s\nMODEL=%s\nTOTAL=%s\nERROR=%s\nUPDATED=%s\n' "$1" "$2" "${3:-0}" "${4:-}" "$(date +%s)" > "$tmp"
    mv -f "$tmp" "$DL_STATE_FILE"
}

# Expected size: the server's Content-Length (after redirects), else the size the app passed.
remote_size() {
    local len
    len=$(curl -sIL --max-time 20 "$URL" 2>/dev/null | tr -d '\r' | grep -i '^content-length:' | tail -1 | awk '{print $2}')
    if [[ "$len" =~ ^[0-9]+$ ]] && [ "$len" -gt 0 ]; then echo "$len"; else echo "${SIZE:-0}"; fi
}

# Runs detached from the bridge call (see cmd_install). Phases: runtime → download → verify → done | error.
cmd_download_worker() {
    local path="$MODELS_DIR/$MODEL" part="$MODELS_DIR/$MODEL.part" total=0
    write_state runtime "$MODEL" 0
    ensure_llama || { write_state error "$MODEL" 0 runtime; die "llama.cpp could not be installed (pkg install llama-cpp)."; }
    total=$(remote_size)
    write_state download "$MODEL" "$total"
    log "⬇️ Downloading $MODEL (resumes a partial download)..."
    # -C - resumes the .part file after an interruption.
    if ! curl --fail --show-error --location --retry 5 --retry-delay 5 -C - "$URL" -o "$part" >&2; then
        write_state error "$MODEL" "$total" network
        die "Download failed (the partial file is kept and resumed next time)."
    fi
    write_state verify "$MODEL" "$total"
    if ! is_valid_gguf "$part"; then
        rm -f "$part"; write_state error "$MODEL" "$total" format
        die "The download is not a GGUF model."
    fi
    if [ -n "$SHA256" ]; then
        if ! echo "$SHA256  $part" | sha256sum -c --status; then
            rm -f "$part"; write_state error "$MODEL" "$total" checksum
            die "SHA-256 mismatch."
        fi
    else
        log "⚠️ No SHA-256 given; only the GGUF header was checked."
    fi
    mv -f "$part" "$path"
    write_state done "$MODEL" "$(file_size "$path")"
    log "✅ Installed $MODEL"
}

cmd_install() {
    [ -n "$MODEL" ] || die "--model is required."
    local path="$MODELS_DIR/$MODEL"
    if download_pid >/dev/null; then
        # A download is running already: report it instead of starting a second one.
        log "⏳ A download is already running."
        cmd_install_status
        return 0
    fi
    if is_valid_gguf "$path"; then
        if [ -z "$SHA256" ] || echo "$SHA256  $path" | sha256sum -c --status 2>/dev/null; then
            log "✅ Model already present: $MODEL"
            write_state done "$MODEL" "$(file_size "$path")"
            echo "status.installed=$MODEL"
            cmd_install_status
            return 0
        fi
        log "⚠️ Existing file does not match the checksum; downloading again."
        rm -f "$path"
    fi
    [ -n "$URL" ] || die "--url is required to download $MODEL."
    case "$URL" in https://*) ;; *) die "Only https URLs are allowed." ;; esac
    local self="${BASH_SOURCE[0]}"
    local args=(_download --model "$MODEL" --url "$URL")
    [ -n "$SHA256" ] && args+=(--sha256 "$SHA256")
    [ -n "$SIZE" ] && args+=(--size "$SIZE")
    write_state runtime "$MODEL" "${SIZE:-0}"
    # setsid + nohup: the download survives the bridge call, the app and the terminal.
    setsid nohup bash "$self" "${args[@]}" >"$DL_LOG_FILE" 2>&1 < /dev/null &
    echo $! > "$DL_PID_FILE"
    log "⬇️ Download started in the background."
    cmd_install_status
}

# status.install_* lines: what the download is doing right now.
cmd_install_status() {
    local PHASE="idle" MODEL_S="" TOTAL=0 ERROR="" running=0 bytes=0
    if [ -f "$DL_STATE_FILE" ]; then
        PHASE=$(sed -n 's/^PHASE=//p' "$DL_STATE_FILE")
        MODEL_S=$(sed -n 's/^MODEL=//p' "$DL_STATE_FILE")
        TOTAL=$(sed -n 's/^TOTAL=//p' "$DL_STATE_FILE")
        ERROR=$(sed -n 's/^ERROR=//p' "$DL_STATE_FILE")
    fi
    download_pid >/dev/null && running=1
    # The worker died mid-way (killed, phone restarted): report it so the app resumes it.
    if [ "$running" = 0 ] && { [ "$PHASE" = runtime ] || [ "$PHASE" = download ] || [ "$PHASE" = verify ]; }; then
        PHASE=interrupted
    fi
    if [ -n "$MODEL_S" ]; then
        if [ -f "$MODELS_DIR/$MODEL_S.part" ]; then bytes=$(file_size "$MODELS_DIR/$MODEL_S.part")
        elif [ -f "$MODELS_DIR/$MODEL_S" ]; then bytes=$(file_size "$MODELS_DIR/$MODEL_S"); fi
    fi
    echo "status.install_phase=${PHASE:-idle}"
    echo "status.install_model=$MODEL_S"
    echo "status.install_bytes=$bytes"
    echo "status.install_total=${TOTAL:-0}"
    echo "status.install_error=$ERROR"
    echo "status.install_running=$running"
}

cmd_cancel() {
    local pid state_model=""
    [ -f "$DL_STATE_FILE" ] && state_model=$(sed -n 's/^MODEL=//p' "$DL_STATE_FILE")
    if pid=$(download_pid); then
        # The worker runs in its own session: stop curl with it.
        kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null
        for _ in 1 2 3 4 5 6 7 8 9 10; do
            kill -0 "$pid" 2>/dev/null || break
            sleep 0.3
        done
        kill -9 -- "-$pid" 2>/dev/null || kill -9 "$pid" 2>/dev/null || true
        log "🛑 Download stopped; the partial file is kept."
    fi
    rm -f "$DL_PID_FILE"
    [ -n "$state_model" ] && write_state cancelled "$state_model" 0
    cmd_install_status
}

cmd_delete() {
    [ -n "$MODEL" ] || die "--model is required."
    local state_model=""
    [ -f "$DL_STATE_FILE" ] && state_model=$(sed -n 's/^MODEL=//p' "$DL_STATE_FILE")
    if [ "$state_model" = "$MODEL" ] && download_pid >/dev/null; then cmd_cancel >/dev/null; fi
    if server_pid >/dev/null; then
        [ -f "$CONF_FILE" ] && . "$CONF_FILE"
        [ "${SERVER_MODEL:-}" = "$MODEL" ] && cmd_stop >/dev/null
    fi
    rm -f "$MODELS_DIR/$MODEL" "$MODELS_DIR/$MODEL.part"
    [ "$state_model" = "$MODEL" ] && rm -f "$DL_STATE_FILE"
    log "🗑️ Deleted $MODEL"
    echo "status.deleted=$MODEL"
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
    cmd_download_worker && cmd_start
    echo "In AMC Settings select 'Local' with http://127.0.0.1:$PORT/v1"
}

case "$CMD" in
    status) cmd_status ;;
    install) cmd_install ;;
    _download) cmd_download_worker ;;
    start) cmd_start ;;
    stop) cmd_stop ;;
    cancel) cmd_cancel ;;
    delete) cmd_delete ;;
    "") cmd_interactive ;;
    *) die "Unknown command '$CMD' (status|install|start|stop|cancel|delete)." ;;
esac
