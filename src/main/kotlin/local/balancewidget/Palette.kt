package local.balancewidget

import android.content.Context
import android.graphics.Color

internal enum class Palette(val title: String, background: String, foreground: String,
    secondary: String, control: String, val dark: Boolean) {
    PINE("Pine", "#21342D", "#EFFBF3", "#BCDBC9", "#355247", true),
    MIDNIGHT("Midnight", "#23314D", "#F1F5FF", "#BFCEE9", "#435676", true),
    GRAPHITE("Graphite", "#26282C", "#F8F8F4", "#CACED3", "#474B51", true),
    AUBERGINE("Aubergine", "#38233B", "#FFF1FA", "#E3C6DE", "#68476B", true),
    SAND("Sand", "#FAF3E0", "#352C1F", "#64533B", "#E9DCC0", false),
    SEA_GLASS("Sea Glass", "#DDEFEA", "#153B34", "#355F55", "#C4DED6", false),
    LILAC("Lilac", "#EDE5F8", "#352749", "#5A4672", "#DDD0EF", false),
    TERRACOTTA("Terracotta", "#F6DDD2", "#492A22", "#713F32", "#EAC4B5", false);

    val surface = Color.parseColor(background)
    val ink = Color.parseColor(foreground)
    val muted = Color.parseColor(secondary)
    val action = Color.parseColor(control)

    companion object {
        private fun preferences(context: Context) =
            context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

        fun current(context: Context): Palette {
            val name = preferences(context).getString("palette", null)
            return entries.firstOrNull { it.name == name } ?: PINE
        }

        fun save(context: Context, palette: Palette) {
            preferences(context).edit().putString("palette", palette.name).apply()
            Widgets.refresh(context)
        }
    }
}
