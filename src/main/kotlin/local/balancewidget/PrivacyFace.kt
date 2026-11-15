package local.balancewidget

import android.content.Context

internal enum class PrivacyFace(val label: String, val symbol: String) {
    SIDE_EYE("Side eye", "(¬_¬)"),
    PEEK("Peek", "(o_o)"),
    WINK("Wink", "(^_~)"),
    COOL("Cool", "B)"),
    SLEEPY("Sleepy", "(-_-)"),
    CAT("Cat", "=^.^="),
    NEUTRAL("Neutral", ":|");

    companion object {
        private fun preferences(context: Context) =
            context.getSharedPreferences("widget_privacy_appearance", Context.MODE_PRIVATE)

        fun current(context: Context): PrivacyFace {
            val saved = preferences(context).getString("face", null)
            return entries.firstOrNull { it.name == saved } ?: SIDE_EYE
        }

        fun save(context: Context, face: PrivacyFace) {
            preferences(context).edit().putString("face", face.name).apply()
            Widgets.refresh(context)
        }
    }
}
