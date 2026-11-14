package io.github.ambitionandvision.tactical.sdk

/**
 * 실행 인자 `laneTestAtSec` 용 1회 예약: GPX 재생을 시작하고 [delaySec] 초 뒤 차선 이탈 테스트를 한 번 실행한다.
 *
 * - [arm] 은 처음 한 번만 예약한다. 실행됐거나 취소된 뒤에는 다시 예약되지 않는다.
 * - 재생이 일시정지·중단되면 [cancel] 로 취소한다. 취소된 예약은 실행되지 않는다.
 *
 * [scheduler] 는 이 예약 전용이어야 한다([FixScheduler.cancelAll] 을 쓴다). 단일 스레드에서 호출한다.
 */
class LaneTestSchedule(
    private val scheduler: FixScheduler,
    val delaySec: Int,
    private val onFire: () -> Unit,
) {
    enum class State { IDLE, ARMED, FIRED, CANCELLED }

    var state = State.IDLE
        private set

    /** 예약했으면 true. 이미 예약·실행·취소된 상태면 아무것도 하지 않고 false. */
    fun arm(): Boolean {
        if (state != State.IDLE) return false
        state = State.ARMED
        scheduler.postDelayed(delaySec.coerceAtLeast(0) * 1000L) {
            if (state == State.ARMED) {
                state = State.FIRED
                onFire()
            }
        }
        return true
    }

    /** 대기 중인 예약을 취소했으면 true. */
    fun cancel(): Boolean {
        if (state != State.ARMED) return false
        state = State.CANCELLED
        scheduler.cancelAll()
        return true
    }

    companion object {
        /** 실행 인자 값 해석: 0 이상이면 예약 대상, 음수(인자 없음 포함)는 null. */
        fun delayFromExtra(value: Int): Int? = if (value >= 0) value else null
    }
}
