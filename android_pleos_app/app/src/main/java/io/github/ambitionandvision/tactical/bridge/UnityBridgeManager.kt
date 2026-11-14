package io.github.ambitionandvision.tactical.bridge

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.ambitionandvision.tactical.model.TacticalFrame
import io.github.ambitionandvision.tactical.model.TacticalSurface
import io.github.ambitionandvision.tactical.model.ZoneData
import java.lang.reflect.Method

/**
 * Unity as a Library(UaaL) 브리지. `com.unity3d.player.UnityPlayer` 를 **리플렉션**으로만 찾으므로
 * Unity 산출물이 없어도 컴파일되고, 그때는 모든 호출을 [fallback](네이티브 Canvas 뷰)에 위임한다.
 *
 * 프로토콜(TEAM_WORKSPACE 5.7): `UnityPlayer.UnitySendMessage("PleosBridge", method, json)`
 * - OnZone(zoneJson) · OnVehicleState(json) · OnActive("true"|"false")
 *
 * Unity 가 있으면 Unity 화면을 쓰려면 호출측이 [attachUnityView] 로 컨테이너를 넘기고
 * 액티비티 수명주기에서 [onResume]/[onPause]/[destroy] 를 불러야 한다. Unity 뷰 생성에 실패하면 폴백으로 돌아간다.
 */
class UnityBridgeManager(
    private val context: Context,
    private val fallback: TacticalSurface,
) : TacticalSurface {

    private val sendMessage: Method? = findSendMessage()
    private var unityPlayer: Any? = null
    private var unityFailed = false
    private var lastZoneJson: String? = null
    private var active = false

    /** UnityPlayer 클래스가 APK 에 있고 지금까지 Unity 호출이 실패하지 않았는가. */
    val isUnityAvailable: Boolean get() = sendMessage != null && !unityFailed

    /**
     * Unity 가 있으면 UnityPlayer 뷰를 만들어 [container] 에 붙이고 true. 없거나 실패하면 false (폴백 유지).
     * [fallbackView] 가 주어지면 Unity 사용 시 숨긴다.
     */
    fun attachUnityView(container: ViewGroup, fallbackView: View? = null): Boolean {
        if (!isUnityAvailable) return false
        if (unityPlayer == null) {
            unityPlayer = try {
                val cls = Class.forName(UNITY_PLAYER_CLASS)
                cls.getConstructor(Context::class.java).newInstance(context)
            } catch (t: Throwable) {
                markFailed("UnityPlayer 생성 실패", t)
                return false
            }
        }
        val view = unityPlayer as? View ?: run { markFailed("UnityPlayer 가 View 가 아님", null); return false }
        if (view.parent == null) {
            container.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        fallbackView?.visibility = View.GONE
        lastZoneJson?.let { send(UnityBridgeProtocol.METHOD_ZONE, it) }
        send(UnityBridgeProtocol.METHOD_ACTIVE, UnityBridgeProtocol.activeArg(active))
        return true
    }

    private val usingUnity: Boolean get() = isUnityAvailable && unityPlayer != null

    override fun bindZone(zone: ZoneData) {
        fallback.bindZone(zone) // Unity 실패 시 즉시 넘어갈 수 있게 폴백도 항상 최신 zone 을 가진다.
        val json = UnityBridgeProtocol.zoneJson(zone)
        lastZoneJson = json
        if (usingUnity) send(UnityBridgeProtocol.METHOD_ZONE, json)
    }

    override fun setActive(active: Boolean) {
        this.active = active
        if (usingUnity) {
            fallback.setActive(false)
            send(UnityBridgeProtocol.METHOD_ACTIVE, UnityBridgeProtocol.activeArg(active))
        } else {
            fallback.setActive(active)
        }
    }

    override fun onFrame(frame: TacticalFrame) {
        if (usingUnity) send(UnityBridgeProtocol.METHOD_VEHICLE_STATE, UnityBridgeProtocol.vehicleStateJson(frame))
        if (!usingUnity) fallback.onFrame(frame)
    }

    fun onResume() = invokePlayer("resume")
    fun onPause() = invokePlayer("pause")
    fun onWindowFocusChanged(hasFocus: Boolean) {
        val p = unityPlayer ?: return
        try {
            p.javaClass.getMethod("windowFocusChanged", Boolean::class.javaPrimitiveType).invoke(p, hasFocus)
        } catch (t: Throwable) {
            Log.w(TAG, "windowFocusChanged 실패", t)
        }
    }

    fun destroy() {
        invokePlayer("destroy")
        unityPlayer = null
    }

    private fun invokePlayer(name: String) {
        val p = unityPlayer ?: return
        try {
            p.javaClass.getMethod(name).invoke(p)
        } catch (t: Throwable) {
            Log.w(TAG, "UnityPlayer.$name 실패", t)
        }
    }

    private fun send(method: String, arg: String) {
        val m = sendMessage ?: return
        try {
            m.invoke(null, UnityBridgeProtocol.GAME_OBJECT, method, arg)
        } catch (t: Throwable) {
            markFailed("UnitySendMessage($method) 실패", t)
        }
    }

    /** Unity 경로를 끄고 폴백으로 복귀한다. */
    private fun markFailed(msg: String, t: Throwable?) {
        Log.w(TAG, "$msg → 네이티브 폴백 사용", t)
        unityFailed = true
        (unityPlayer as? View)?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        (fallback as? View)?.visibility = View.VISIBLE
        fallback.setActive(active)
    }

    companion object {
        private const val TAG = "UnityBridge"
        const val UNITY_PLAYER_CLASS = "com.unity3d.player.UnityPlayer"

        /** UnityPlayer.UnitySendMessage(String, String, String) 정적 메서드를 찾는다. 없으면 null. */
        fun findSendMessage(): Method? = try {
            Class.forName(UNITY_PLAYER_CLASS)
                .getMethod("UnitySendMessage", String::class.java, String::class.java, String::class.java)
        } catch (_: Throwable) {
            null
        }
    }
}
