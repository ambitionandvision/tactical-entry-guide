package io.github.ambitionandvision.tactical.sdk

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.ambitionandvision.tactical.model.FixSource
import io.github.ambitionandvision.tactical.model.FuelKind
import io.github.ambitionandvision.tactical.model.LatLon
import io.github.ambitionandvision.tactical.model.LocationSource
import io.github.ambitionandvision.tactical.model.VehicleFix

// Pleos Connect SDK 래퍼 (TEAM_WORKSPACE 3.2 의 실제 API 만 사용).
// 모든 호출은 isSupported()/예외로 방어한다. SDK 서비스가 없는 환경에서도 앱은 죽지 않고 데모 소스로 동작한다.
// 콜백은 모두 메인 스레드로 넘긴다.

private const val TAG = "PleosSdkAdapter"

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

private inline fun <T> guarded(what: String, fallback: T, block: () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "$what 실패: ${e.message}")
        fallback
    } catch (e: LinkageError) {
        // SDK 서비스/클래스가 없는 일반 에뮬레이터 등
        Log.w(TAG, "$what 사용 불가: ${e.message}")
        fallback
    }

/** 연료 판별 규칙 (TEAM_WORKSPACE 4장). CarInfo 에는 연료 타입·전고 API 가 없다. */
object FuelKindRules {
    fun decide(evSocSupported: Boolean, fuelSupported: Boolean): FuelKind = when {
        evSocSupported && fuelSupported -> FuelKind.PHEV
        evSocSupported -> FuelKind.EV
        fuelSupported -> FuelKind.ICE
        else -> FuelKind.UNKNOWN
    }
}

/**
 * FusedLocation 인스턴스 하나를 연속 측위·마지막 위치·ProximityAlert 가 함께 쓴다.
 * 모든 호출은 예외로 방어하고, 미지원·실패 시 false/null 을 돌려준다.
 */
class PleosFusedClient(context: Context) {
    private val appContext = context.applicationContext
    private var fused: FusedLocation? = null
    private var initFailed = false

    fun isSupported(): Boolean = guarded("FusedLocation.isSupported", false) {
        FusedLocation(appContext).isSupported()
    }

    /** 초기화된 인스턴스. 미지원·실패면 null. */
    fun get(): FusedLocation? {
        fused?.let { return it }
        if (initFailed) return null
        fused = guarded("FusedLocation 초기화", null) {
            val f = FusedLocation(appContext)
            if (!f.isSupported()) {
                Log.i(TAG, "FusedLocation 미지원")
                return@guarded null
            }
            f.initialize()
            f
        }
        if (fused == null) initFailed = true
        return fused
    }

    fun release() {
        fused?.let { f -> guarded("FusedLocation 해제", Unit) { f.release() } }
        fused = null
        initFailed = false
    }
}

/** Pleos FusedLocation 연속 측위. 속도는 [SpeedEstimator] 로 추정한다(LocationData 에 속도 없음). */
class FusedLocationSource(private val client: PleosFusedClient) : LocationSource {
    private var listener: ((VehicleFix) -> Unit)? = null
    private val estimator = SpeedEstimator()
    private var registeredOn: FusedLocation? = null

    private val callback = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            val loc = location ?: return
            mainHandler.post { deliver(loc) }
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "FusedLocation onError: ${exception.message}")
        }
    }

    private val lastKnownCallback = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            val loc = location ?: return
            mainHandler.post { onLastKnown?.invoke(loc.latitude, loc.longitude) }
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "getLastKnownLocation onError: ${exception.message}")
        }
    }

    /** getLastKnownLocation 결과(메인 스레드). 상태머신에는 넣지 않는다(오래된 값·속도 없음). */
    var onLastKnown: ((lat: Double, lon: Double) -> Unit)? = null

    val isRunning: Boolean get() = registeredOn != null

    override fun start(listener: (VehicleFix) -> Unit) {
        this.listener = listener
        if (registeredOn != null) return
        val f = client.get() ?: return
        if (guarded("registerFusedLocationCallback", false) { f.registerFusedLocationCallback(callback); true }) {
            registeredOn = f
            Log.i(TAG, "FusedLocation 연속 측위 구독 시작")
        }
    }

    override fun stop() {
        listener = null
        registeredOn?.let { f ->
            guarded("unregisterFusedLocationCallback", Unit) { f.unregisterFusedLocationCallback(callback) }
            Log.i(TAG, "FusedLocation 연속 측위 구독 해제")
        }
        registeredOn = null
        estimator.reset()
    }

    fun requestLastKnown(): Boolean {
        val f = client.get() ?: return false
        return guarded("getLastKnownLocation", false) { f.getLastKnownLocation(lastKnownCallback); true }
    }

    private fun deliver(loc: LocationData) {
        val l = listener ?: return
        val speed = estimator.update(loc.latitude, loc.longitude, loc.timestampMillis)
        l(
            VehicleFix(
                lat = loc.latitude,
                lon = loc.longitude,
                headingDeg = loc.heading,
                speedKmh = speed,
                timestampMs = loc.timestampMillis,
                source = FixSource.PLEOS_FUSED,
            ),
        )
    }
}

/**
 * 활성 입구 중심 ProximityAlert(ENTER, EXIT). 등록·전이·오류는 메인 스레드에서 [generation] 과 함께 전달한다.
 * 실패해도 [LocationSubscriptionController] 가 연속 측위로 폴백하므로 기능에는 영향이 없다.
 */
class ZoneGeofence(private val client: PleosFusedClient) {
    private var alertId: String? = null
    private var generation = 0

    var onRegistered: ((generation: Int) -> Unit)? = null
    var onTransition: ((enter: Boolean) -> Unit)? = null
    var onFailed: ((generation: Int, reason: String) -> Unit)? = null

    fun register(center: LatLon, radiusM: Float, generation: Int) {
        unregister()
        this.generation = generation
        val f = client.get()
        if (f == null) {
            mainHandler.post { onFailed?.invoke(generation, "FusedLocation 미지원/초기화 실패") }
            return
        }
        val listener = object : ProximityAlertListener {
            override fun onRegistered(alertId: String) {
                mainHandler.post {
                    if (generation != this@ZoneGeofence.generation) {
                        guarded("이전 ProximityAlert 해제", Unit) { f.removeProximityAlert(alertId) }
                        return@post
                    }
                    this@ZoneGeofence.alertId = alertId
                    Log.i(TAG, "ProximityAlert 등록: $alertId (${center.lat},${center.lon} r=${radiusM}m)")
                    onRegistered?.invoke(generation)
                }
            }

            override fun onTransition(event: ProximityAlertEvent) {
                mainHandler.post {
                    if (generation != this@ZoneGeofence.generation) return@post
                    if (alertId != null && event.alertId != alertId) return@post
                    Log.i(TAG, "ProximityAlert 전이: ${event.transition.name}")
                    onTransition?.invoke(event.transition == ProximityTransition.ENTER)
                }
            }

            override fun onError(exception: Exception) {
                mainHandler.post { onFailed?.invoke(generation, exception.message ?: exception.javaClass.simpleName) }
            }
        }
        val ok = guarded("addProximityAlert", false) {
            f.addProximityAlert(
                ProximityAlertRequest(center.lat, center.lon, radiusM, setOf(ProximityTransition.ENTER, ProximityTransition.EXIT)),
                listener,
            )
            true
        }
        if (!ok) mainHandler.post { onFailed?.invoke(generation, "addProximityAlert 예외") }
    }

    fun unregister() {
        val id = alertId ?: return
        client.get()?.let { f -> guarded("removeProximityAlert", Unit) { f.removeProximityAlert(id) } }
        alertId = null
    }
}

/**
 * 연료 타입 판별: EvBattery SoC capability 와 Fuel capability 조합 ([FuelKindRules]).
 * capability 지원 = CapabilityConfig.accessType != AccessType.NONE (Pleos 공개 샘플과 같은 판정).
 * 응답이 [timeoutMs] 안에 오지 않은 쪽은 미지원으로 본다. 결과는 메인 스레드에서 한 번 전달된다.
 * 차량 전고 등 CarInfo 기반 필터는 쓰지 않는다.
 */
class FuelKindDetector(context: Context, private val timeoutMs: Long = 3_000L) {
    private val appContext = context.applicationContext
    private var vehicle: Vehicle? = null

    fun detect(onResult: (FuelKind) -> Unit) {
        var ev: Boolean? = null
        var fuel: Boolean? = null
        var done = false
        fun finish(force: Boolean) {
            if (done) return
            if (!force && (ev == null || fuel == null)) return
            done = true
            val kind = FuelKindRules.decide(ev == true, fuel == true)
            Log.i(TAG, "연료 판별: evSoc=$ev fuel=$fuel → $kind")
            onResult(kind)
        }

        val v = guarded("Vehicle 초기화", null) { Vehicle(appContext).also { it.initialize() } }
        vehicle = v
        if (v == null) {
            ev = false; fuel = false
            finish(true)
            return
        }
        guarded("EvBattery capability", Unit) {
            v.getEvBattery().checkEvBatteryStateOfChargeCapabilities(
                { cfg -> mainHandler.post { ev = cfg.accessType != AccessType.NONE; finish(false) } },
                { e -> mainHandler.post { Log.i(TAG, "EvBattery SoC 미지원: ${e.message}"); ev = false; finish(false) } },
            )
        }
        guarded("Fuel capability", Unit) {
            v.getFuel().checkFuelPercentCapabilities(
                { cfg -> mainHandler.post { fuel = cfg.accessType != AccessType.NONE; finish(false) } },
                { e -> mainHandler.post { Log.i(TAG, "Fuel 미지원: ${e.message}"); fuel = false; finish(false) } },
            )
        }
        mainHandler.postDelayed({ finish(true) }, timeoutMs)
    }

    fun release() {
        vehicle?.let { v -> guarded("Vehicle 해제", Unit) { v.release() } }
        vehicle = null
    }
}

/**
 * NaviHelper 목적지 감시. 결과는 [NaviZoneGate] 로 해석해 메인 스레드로 전달한다.
 * 경로 시작·종료·취소·목적지 변경 시 목적지를 다시 조회한다. 초기화 실패는 [onUnavailable] 로 알린다.
 */
class NaviDestinationMonitor(context: Context, private val gate: NaviZoneGate) {
    private val appContext = context.applicationContext
    private var helper: NaviHelper? = null

    var onStatus: ((NaviDestinationStatus) -> Unit)? = null
    var onUnavailable: (() -> Unit)? = null
    var lastStatus: NaviDestinationStatus? = null
        private set

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            val d = destinationInfo
            val status = gate.classify(d.name, d.latitude, d.longitude, d.remainDistance)
            mainHandler.post {
                lastStatus = status
                Log.i(
                    TAG,
                    "목적지: route=${status.hasActiveRoute} nearZone=${status.nearZone} " +
                        "d=${status.distanceToZoneM}m remain=${d.remainDistance}m",
                )
                onStatus?.invoke(status)
            }
        }

        override fun onDestinationChanged() = refresh()
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = refresh()
        override fun onRouteEnded() = refresh()
        override fun onRouteCancelled() = refresh()
    }

    fun start() {
        if (helper != null) return
        helper = guarded("NaviHelper 시작", null) {
            NaviHelper(appContext).also {
                it.initialize()
                it.addListener(listener)
                it.getDestinationInfo()
            }
        }
        if (helper == null) onUnavailable?.invoke()
    }

    fun refresh() {
        helper?.let { h -> guarded("NaviHelper.getDestinationInfo", Unit) { h.getDestinationInfo() } }
    }

    fun stop() {
        helper?.let { h ->
            guarded("NaviHelper 정지", Unit) {
                h.removeListener(listener)
                h.release()
            }
        }
        helper = null
    }
}

/**
 * AAOS CarUxRestrictions 모니터. FEATURE_AUTOMOTIVE 기기에서만 Car.createCar 를 호출한다.
 * 주행 제한(DO 필요 또는 UX_RESTRICTIONS_NO_VIDEO)이면 true 를 메인 스레드로 전달한다.
 */
class UxRestrictionMonitor(context: Context, private val onChanged: (Boolean) -> Unit) {
    private val appContext = context.applicationContext
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    private val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r ->
        mainHandler.post { onChanged(isRestricted(r)) }
    }

    fun start() {
        if (car != null) return
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            Log.i(TAG, "FEATURE_AUTOMOTIVE 아님 → CarUxRestrictions 미사용")
            return
        }
        guarded("CarUxRestrictions 시작", Unit) {
            val c = Car.createCar(appContext) ?: return@guarded
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            car = c
            manager = m
            if (m != null) {
                m.registerListener(listener)
                m.currentCarUxRestrictions?.let { onChanged(isRestricted(it)) }
            }
        }
    }

    fun stop() {
        guarded("CarUxRestrictions 정지", Unit) {
            manager?.unregisterListener()
            car?.disconnect()
        }
        manager = null
        car = null
    }

    companion object {
        fun isRestricted(r: CarUxRestrictions): Boolean =
            r.isRequiresDistractionOptimization ||
                (r.activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO) != 0
    }
}
