package io.github.ambitionandvision.tactical.sdk

import io.github.ambitionandvision.tactical.model.LatLon

/**
 * 일반 모드(Pleos FusedLocation) 의 측위 구독 전환. Android 의존 없음 — JVM 테스트 대상.
 *
 * - Zone 밖: 연속 측위를 구독하지 않는다. ProximityAlert(ENTER, EXIT) + getLastKnownLocation 1회.
 * - ENTER, 또는 측위(마지막 위치 포함)가 활성 입구에서 [enterDistM] 안: 연속 측위 시작.
 * - EXIT 이고 Phase 1: 연속 측위 해제.
 * - 폴백: 지오펜스 등록 실패 또는 [registerTimeoutMs] 안에 미등록이면 연속 측위를 즉시 시작하고 유지한다.
 * - 지오펜스가 끝내 울리지 않는 경우 대비: 연속 측위가 꺼져 있으면 [pollIntervalMs] 마다 getLastKnownLocation 1회.
 * - 지오펜스 중심은 활성 입구 위치. 입구가 바뀌면 재등록한다.
 */
class LocationSubscriptionController(
    private val actions: Actions,
    private val scheduler: FixScheduler,
    private val enterDistM: Float,
    private val registerTimeoutMs: Long = 3_000L,
    private val pollIntervalMs: Long = 30_000L,
) {
    interface Actions {
        fun registerGeofence(center: LatLon, radiusM: Float, generation: Int)
        fun unregisterGeofence()
        fun requestLastKnown()
        fun startContinuous()
        fun stopContinuous()
        fun log(message: String) {}
    }

    enum class Mode { IDLE, WATCHING, CONTINUOUS_BY_ZONE, CONTINUOUS_FALLBACK }

    var mode: Mode = Mode.IDLE
        private set
    val continuous: Boolean get() = mode == Mode.CONTINUOUS_BY_ZONE || mode == Mode.CONTINUOUS_FALLBACK

    private var center: LatLon? = null
    private var generation = 0
    private var registered = false
    private var running = false

    /** 상태머신이 Phase 1 인가. EXIT 시 해제 여부 판단에 쓴다. */
    var isPhase1: () -> Boolean = { true }

    fun start(entryCenter: LatLon) {
        running = true
        center = entryCenter
        mode = Mode.WATCHING
        register()
        actions.requestLastKnown()
        schedulePoll()
    }

    fun stop() {
        running = false
        scheduler.cancelAll()
        actions.unregisterGeofence()
        if (continuous) actions.stopContinuous()
        mode = Mode.IDLE
        registered = false
    }

    /** 활성 입구가 바뀌면 지오펜스를 새 입구로 다시 건다. */
    fun setEntryCenter(entryCenter: LatLon) {
        if (entryCenter == center) return
        center = entryCenter
        if (!running) return
        actions.unregisterGeofence()
        register()
    }

    fun onGeofenceRegistered(generation: Int) {
        if (generation != this.generation) return
        registered = true
        actions.log("지오펜스 등록 완료(gen=$generation)")
    }

    fun onGeofenceFailed(generation: Int, reason: String) {
        if (!running || generation != this.generation) return
        actions.log("지오펜스 실패: $reason → 연속 측위 폴백")
        goContinuous(Mode.CONTINUOUS_FALLBACK)
    }

    fun onEnter() {
        if (!running) return
        actions.log("지오펜스 ENTER")
        if (!continuous) goContinuous(Mode.CONTINUOUS_BY_ZONE)
    }

    fun onExit() {
        if (!running) return
        actions.log("지오펜스 EXIT (phase1=${isPhase1()})")
        if (mode == Mode.CONTINUOUS_BY_ZONE && isPhase1()) {
            actions.stopContinuous()
            mode = Mode.WATCHING
            schedulePoll()
        }
    }

    /** 마지막 위치 또는 연속 측위의 활성 입구까지 거리. 지오펜스 없이도 Zone 안이면 연속 측위를 켠다. */
    fun onDistance(distanceToEntryM: Float) {
        if (!running) return
        if (!continuous && distanceToEntryM <= enterDistM) {
            actions.log("입구 ${distanceToEntryM.toInt()}m: Zone 안 → 연속 측위")
            goContinuous(Mode.CONTINUOUS_BY_ZONE)
        }
    }

    private fun register() {
        val c = center ?: return
        registered = false
        val gen = ++generation
        actions.registerGeofence(c, enterDistM, gen)
        scheduler.postDelayed(registerTimeoutMs) {
            if (running && gen == generation && !registered && !continuous) {
                actions.log("지오펜스 ${registerTimeoutMs}ms 내 미등록 → 연속 측위 폴백")
                goContinuous(Mode.CONTINUOUS_FALLBACK)
            }
        }
    }

    private fun goContinuous(m: Mode) {
        if (!continuous) actions.startContinuous()
        // 폴백은 더 강한 모드다. ZONE → FALLBACK 승격만 허용.
        if (mode != Mode.CONTINUOUS_FALLBACK) mode = m
    }

    private var pollGeneration = 0

    private fun schedulePoll() {
        val gen = ++pollGeneration // 이전 폴링 루프는 무효화(중복 루프 방지)
        scheduler.postDelayed(pollIntervalMs) {
            if (!running || gen != pollGeneration) return@postDelayed
            if (!continuous) {
                actions.requestLastKnown()
                schedulePoll()
            }
        }
    }
}
