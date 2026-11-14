package io.github.ambitionandvision.tactical.sdk

import android.os.Handler
import android.os.Looper

/** 위치 소스의 주기 실행. 앱은 메인 Looper, JVM 테스트는 가짜 구현을 쓴다. */
interface FixScheduler {
    fun postDelayed(delayMs: Long, task: () -> Unit)
    fun cancelAll()
    fun nowMs(): Long
}

class MainLooperScheduler : FixScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun postDelayed(delayMs: Long, task: () -> Unit) {
        handler.postDelayed(task, delayMs)
    }

    override fun cancelAll() {
        handler.removeCallbacksAndMessages(null)
    }

    override fun nowMs(): Long = System.currentTimeMillis()
}
