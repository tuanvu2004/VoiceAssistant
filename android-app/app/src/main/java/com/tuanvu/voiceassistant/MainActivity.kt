package com.tuanvu.voiceassistant

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
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

        // Hiệu ứng xoay nhẹ lấp lánh cho biểu tượng sao AI trên thanh nhận diện
        ObjectAnimator.ofFloat(binding.ivAiSparkle, View.ROTATION, 0f, 18f, -18f, 0f).apply {
            duration = 4200
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Đổi phong cách nhận diện 3D khi chạm vào viên thuốc thương hiệu hoặc sân khấu 3D
        binding.aiStage.onStyleChanged = { style ->
            binding.tvAiSub.text = "${style.tenHienThi} • Chạm đổi"
        }
        binding.pillAiIdentity.setOnClickListener {
            binding.aiStage.nextStyle()
        }

        // Bấm micro: chờ -> nghe; đang nghe -> xử lý -> nói -> chờ
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
        binding.btnMic.state = s
        binding.aiStage.state = s

        // Đồng hồ chỉ chạy khi đang nghe
        handler.removeCallbacks(demTime)
        if (s == VoiceState.LISTENING) {
            giayNghe = 0
            capNhatDongHo()
            handler.postDelayed(demTime, 1000)
        }

        val dangNghe = s == VoiceState.LISTENING
        binding.tvThoiGian.animate()
            .alpha(if (dangNghe) 1f else 0f)
            .setDuration(320).start()

        val tieuDe = when (s) {
            VoiceState.IDLE -> "Tôi có thể giúp gì cho bạn?"
            VoiceState.LISTENING -> ""
            VoiceState.THINKING -> "Đang phân tích yêu cầu…"
            VoiceState.SPEAKING -> "Hôm nay trời nắng đẹp, 31°C"
        }
        binding.tvTieuDeChinh.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                binding.tvTieuDeChinh.text = tieuDe
                binding.tvTieuDeChinh.animate()
                    .alpha(if (dangNghe) 0f else 1f)
                    .setDuration(260)
                    .start()
            }.start()

        val chu = when (s) {
            VoiceState.IDLE -> "Chạm micro để nói • Vuốt tâm 3D để xoay"
            VoiceState.LISTENING -> "Đang lắng nghe giọng nói của bạn…"
            VoiceState.THINKING -> "Hệ thống AI đang tổng hợp phản hồi"
            VoiceState.SPEAKING -> "Đang phát phản hồi • Chạm X để dừng"
        }
        binding.tvTrangThai.animate().alpha(0f).setDuration(150).withEndAction {
            binding.tvTrangThai.text = chu
            binding.tvTrangThai.animate().alpha(1f).setDuration(280).start()
        }.start()
    }

    private fun capNhatDongHo() {
        binding.tvThoiGian.text = "%02d:%02d".format(giayNghe / 60, giayNghe % 60)
    }
}
