package com.tuanvu.voiceassistant

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tuanvu.voiceassistant.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var trangThai = VoiceState.IDLE
    private var giayNghe = 0

    private val demTime = object : Runnable {
        override fun run() {
            giayNghe++
            capNhatDongHo()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Bấm micro: chờ -> nghe; đang nghe -> xử lý -> nói -> chờ (chỉ để thử giao diện)
        binding.btnMic.setOnClickListener {
            when (trangThai) {
                VoiceState.IDLE -> chuyen(VoiceState.LISTENING)
                VoiceState.LISTENING -> {
                    chuyen(VoiceState.THINKING)
                    handler.postDelayed({ chuyen(VoiceState.SPEAKING) }, 1600)
                    handler.postDelayed({ chuyen(VoiceState.IDLE) }, 5600)
                }
                else -> Unit
            }
        }
        binding.btnDong.setOnClickListener {
            handler.removeCallbacksAndMessages(null)
            chuyen(VoiceState.IDLE)
        }

        val sapCo = { ten: String ->
            Toast.makeText(this, "$ten: sắp có", Toast.LENGTH_SHORT).show()
        }
        binding.btnKho.setOnClickListener { sapCo("Kho giọng") }
        binding.btnLichSu.setOnClickListener { sapCo("Lịch sử") }
        binding.btnCaiDat.setOnClickListener { sapCo("Cài đặt") }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun chuyen(s: VoiceState) {
        trangThai = s

        // Đồng hồ chỉ chạy khi đang nghe
        handler.removeCallbacks(demTime)
        if (s == VoiceState.LISTENING) {
            giayNghe = 0
            capNhatDongHo()
            handler.postDelayed(demTime, 1000)
        }
        binding.tvThoiGian.animate()
            .alpha(if (s == VoiceState.LISTENING) 1f else 0f)
            .setDuration(400).start()

        val chu = when (s) {
            VoiceState.IDLE -> "Chạm micro để nói"
            VoiceState.LISTENING -> "Đang nghe…"
            VoiceState.THINKING -> "Đang xử lý…"
            VoiceState.SPEAKING -> "Hôm nay trời nắng, 31 độ"
        }
        binding.tvTrangThai.animate().alpha(0f).setDuration(180).withEndAction {
            binding.tvTrangThai.text = chu
            binding.tvTrangThai.animate().alpha(1f).setDuration(320).start()
        }.start()
    }

    private fun capNhatDongHo() {
        binding.tvThoiGian.text = "%02d:%02d".format(giayNghe / 60, giayNghe % 60)
    }
}
