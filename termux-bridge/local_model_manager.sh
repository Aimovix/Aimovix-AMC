#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# Local LLM Manager for Termux (llama.cpp / llama-server)
# ==============================================================================

set -e

MODELS_DIR="$HOME/.termux_agent/models"
mkdir -p "$MODELS_DIR"

echo "🧠 [1/3] Prüfe llama.cpp Installation..."
if ! command -v llama-server &> /dev/null; then
    echo "📦 Installiere llama.cpp..."
    pkg install -y llama.cpp 2>/dev/null || {
        echo "Kompiliere llama.cpp aus Quellcode..."
        pkg install -y git cmake clang make
        cd "$HOME"
        git clone --depth 1 https://github.com/ggerganov/llama.cpp.git
        cd llama.cpp
        cmake -B build
        cmake --build build --config Release -j 4
        cp build/bin/llama-server "$PREFIX/bin/"
    }
fi

echo "📥 [2/3] Lokales Modell auswählen..."
echo "1) Qwen 2.5 1.5B Instruct (Q4_K_M, ~1.0 GB RAM - Sehr schnell)"
echo "2) SmolLM2 1.7B Instruct (Q4_K_M, ~1.1 GB RAM - Kompakt)"
echo "3) Qwen 2.5 3B Instruct (Q4_K_M, ~2.1 GB RAM - Leistungsstark)"

read -p "Auswahl [1-3, Standard: 1]: " CHOICE
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
        echo "Ungültige Auswahl, nutze Standard (Qwen 2.5 1.5B)."
        MODEL_NAME="qwen2.5-1.5b-instruct-q4_k_m.gguf"
        MODEL_URL="https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
        ;;
esac

MODEL_PATH="$MODELS_DIR/$MODEL_NAME"

if [ ! -f "$MODEL_PATH" ]; then
    echo "⬇️ Lade $MODEL_NAME herunter..."
    curl -L "$MODEL_URL" -o "$MODEL_PATH"
else
    echo "✅ Modell bereits vorhanden: $MODEL_NAME"
fi

echo "🚀 [3/3] Starte lokalen Inferenz-Server auf http://127.0.0.1:8080..."
echo "In deiner App unter Einstellungen: 'Lokaler Server' auswählen mit http://127.0.0.1:8080/v1"
echo "Drücke Strg+C zum Beenden."

exec llama-server \
    -m "$MODEL_PATH" \
    --host 127.0.0.1 \
    --port 8080 \
    -c 2048 \
    --threads 4 \
    --metrics
