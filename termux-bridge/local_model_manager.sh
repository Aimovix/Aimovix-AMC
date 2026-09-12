#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# Local LLM Manager for Termux (llama.cpp / llama-server)
# ==============================================================================

set -e

MODELS_DIR="$HOME/.termux_agent/models"
mkdir -p "$MODELS_DIR"

echo "🧠 [1/3] Checking llama.cpp installation..."
if ! command -v llama-server &> /dev/null; then
    echo "📦 Installing llama.cpp..."
    pkg install -y llama.cpp 2>/dev/null || {
        echo "Building llama.cpp from source..."
        pkg install -y git cmake clang make
        cd "$HOME"
        git clone --depth 1 https://github.com/ggerganov/llama.cpp.git
        cd llama.cpp
        cmake -B build
        cmake --build build --config Release -j 4
        cp build/bin/llama-server "$PREFIX/bin/"
    }
fi

echo "📥 [2/3] Select a local model..."
echo "1) Qwen 2.5 1.5B Instruct (Q4_K_M, ~1.0 GB RAM - Very fast)"
echo "2) SmolLM2 1.7B Instruct (Q4_K_M, ~1.1 GB RAM - Compact)"
echo "3) Qwen 2.5 3B Instruct (Q4_K_M, ~2.1 GB RAM - More capable)"

read -p "Selection [1-3, default: 1]: " CHOICE
CHOICE=${CHOICE:-1}

case $CHOICE in
    1)
        MODEL_NAME="qwen2.5-1.5b-instruct-q4_k_m.gguf"
        MODEL_URL="https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
        ;;
    2)
        MODEL_NAME="smollm2-1.7b-instruct-q4_k_m.gguf"
        MODEL_URL="https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf"
        ;;
    3)
        MODEL_NAME="qwen2.5-3b-instruct-q4_k_m.gguf"
        MODEL_URL="https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf"
        ;;
    *)
        echo "Invalid selection; using the default (Qwen 2.5 1.5B)."
        MODEL_NAME="qwen2.5-1.5b-instruct-q4_k_m.gguf"
        MODEL_URL="https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
        ;;
esac

MODEL_PATH="$MODELS_DIR/$MODEL_NAME"
PART_PATH="${MODEL_PATH}.part"

if [ ! -f "$MODEL_PATH" ]; then
    echo "⬇️ Downloading $MODEL_NAME..."
    rm -f "$PART_PATH"
    if curl --fail --show-error -L "$MODEL_URL" -o "$PART_PATH"; then
        if [ -s "$PART_PATH" ] && [ "$(wc -c < "$PART_PATH")" -ge 104857600 ]; then
            mv "$PART_PATH" "$MODEL_PATH"
            echo "✅ Model download complete: $MODEL_NAME"
        else
            echo "❌ Downloaded file is invalid or too small. Cleaning up..." >&2
            rm -f "$PART_PATH"
            exit 1
        fi
    else
        echo "❌ Download failed." >&2
        rm -f "$PART_PATH"
        exit 1
    fi
else
    echo "✅ Model already downloaded: $MODEL_NAME"
fi

echo "🚀 [3/3] Starting local inference server at http://127.0.0.1:8080..."
echo "In app Settings, select 'Local server' with http://127.0.0.1:8080/v1"
echo "Press Ctrl+C to stop."

exec llama-server \
    -m "$MODEL_PATH" \
    --host 127.0.0.1 \
    --port 8080 \
    -c 4096 \
    --threads 4 \
    --metrics
