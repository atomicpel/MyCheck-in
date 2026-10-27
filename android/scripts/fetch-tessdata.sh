#!/usr/bin/env bash
# Скачать словарь русского языка для Tesseract в assets приложения.
set -euo pipefail
dir="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/tessdata"
mkdir -p "$dir"
if [ ! -s "$dir/rus.traineddata" ]; then
  curl -fsSL -o "$dir/rus.traineddata" https://github.com/tesseract-ocr/tessdata_best/raw/main/rus.traineddata
fi
ls -la "$dir"
