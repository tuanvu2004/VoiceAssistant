package com.tuanvu.voiceassistant

/** Trạng thái của trợ lý trên giao diện. */
enum class VoiceState(val moTa: String) {
    IDLE("Trợ lý đang chờ"),
    LISTENING("Trợ lý đang nghe"),
    THINKING("Trợ lý đang xử lý"),
    SPEAKING("Trợ lý đang nói")
}
