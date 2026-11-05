File model `.onnx` trong thư mục này không được đưa lên git (xem `.gitignore`).

Cần copy từ `python-training/` sang đây trước khi build:
- `silero_vad.onnx` — model phát hiện khoảng lặng giọng nói (VAD).
- (sau này) model ASR `sherpa-onnx-zipformer-vi-30M-int8` và model intent đã export ONNX.
