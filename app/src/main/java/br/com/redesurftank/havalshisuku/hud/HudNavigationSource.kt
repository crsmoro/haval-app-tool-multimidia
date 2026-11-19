package br.com.redesurftank.havalshisuku.hud

import br.com.redesurftank.havalshisuku.managers.AndroidAutoNavigationTelemetry.Directions

/** Selects the single guidance source shown by the HUD; native Waze wins only while active. */
internal object HudNavigationSource {
    enum class Kind { NONE, ANDROID_AUTO, NATIVE_WAZE }

    data class Selection(val kind: Kind, val directions: Directions?)

    fun select(androidAuto: Directions?, nativeWaze: Directions?): Selection {
        nativeWaze?.takeIf { it.active }?.let {
            return Selection(Kind.NATIVE_WAZE, it)
        }
        androidAuto?.takeIf { it.active }?.let {
            return Selection(Kind.ANDROID_AUTO, it)
        }
        return Selection(Kind.NONE, null)
    }
}
