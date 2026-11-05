package com.tuanvu.voiceassistant

import kotlin.math.max
import kotlin.math.roundToInt

/** Cỡ ô pixel dùng chung cho mọi nút, để toàn bộ giao diện có cùng độ "thô". */
object PixelStyle {
    /** Một ô pixel ~2,4dp (nhỏ hơn bản đầu 3dp một chút). */
    private const val CELL_DP = 2.4f

    /** Số pixel thật của một ô (số nguyên để mọi ô to bằng nhau). */
    fun cellPx(density: Float): Int = max(2, (CELL_DP * density).roundToInt())
}

/** Tranh pixel của các icon. 'X' = bật, '.' = trống. Chiều rộng nên là số lẻ để cân giữa. */
object PixelArt {
    val HISTORY = arrayOf(
        "...XXXXX...",
        "..XX...XX..",
        ".XX..X..XX.",
        "XX...X...XX",
        "X....X....X",
        "X....XXX..X",
        "X.........X",
        "XX.......XX",
        ".XX.....XX.",
        "..XX...XX..",
        "...XXXXX..."
    )

    /** Bánh răng 8 răng, lỗ 3x3 ở giữa. */
    val SETTINGS = arrayOf(
        ".....XXX.....",
        "...X.XXX.X...",
        "..XXXXXXXXX..",
        ".XXXXXXXXXXX.",
        "..XXXXXXXXX..",
        "XXXXX...XXXXX",
        "XXXXX...XXXXX",
        "XXXXX...XXXXX",
        "..XXXXXXXXX..",
        ".XXXXXXXXXXX.",
        "..XXXXXXXXX..",
        "...X.XXX.X...",
        ".....XXX....."
    )

    /** Kho giọng nói: thư mục có các thanh sóng âm khoét ở giữa. */
    val VAULT = arrayOf(
        "XXXXX........",
        "XXXXXXXXXXXXX",
        "XXXXXXX.XXXXX",
        "XXXXX.X.XXXXX",
        "XXX.X.X.X.XXX",
        "XXX.X.X.X.XXX",
        "XXX.X.X.X.XXX",
        "XXXXX.X.XXXXX",
        "XXXXXXX.XXXXX",
        "XXXXXXXXXXXXX"
    )

    val CLOSE = arrayOf(
        "XX.....XX",
        "XXX...XXX",
        ".XXX.XXX.",
        "..XXXXX..",
        "...XXX...",
        "..XXXXX..",
        ".XXX.XXX.",
        "XXX...XXX",
        "XX.....XX"
    )

    val MIC = arrayOf(
        "...XXX...",
        "..XXXXX..",
        "..XXXXX..",
        "..XXXXX..",
        "..XXXXX..",
        "X.XXXXX.X",
        "X..XXX..X",
        ".X.....X.",
        "..XXXXX..",
        "....X....",
        "....X....",
        "..XXXXX.."
    )
}
