package fr.micolomike.mestransports

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Pastille de ligne aux couleurs IDFM : un rond avec le code de la ligne (14, E, T3…),
 * une pilule si le code est trop long pour un rond (bus 4 caractères, N140…).
 */
object Pastille {
    private val cache = mutableMapOf<String, Bitmap>()

    fun couleur(hex: String, defaut: Int): Int = try {
        Color.parseColor("#" + hex.removePrefix("#"))
    } catch (e: Exception) {
        defaut
    }

    fun dessiner(ctx: Context, ligne: Ligne, tailleDp: Int = 26): Bitmap {
        val cleCache = "${ligne.code}|${ligne.couleur}|${ligne.couleurTexte}|$tailleDp"
        cache[cleCache]?.let { return it }

        val d = ctx.resources.displayMetrics.density
        val h = (tailleDp * d).toInt().coerceAtLeast(8)
        val code = ligne.code.ifEmpty { "?" }
        val rond = code.length <= 3

        val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = couleur(ligne.couleurTexte, Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = h * (if (code.length <= 1) 0.62f else if (code.length == 2) 0.5f else 0.46f)
        }
        val w = if (rond) h else maxOf(h, (texte.measureText(code) + h * 0.6f).toInt())

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fond = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = couleur(ligne.couleur, Color.GRAY) }
        if (rond) c.drawCircle(w / 2f, h / 2f, h / 2f, fond)
        else c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, fond)

        val y = h / 2f - (texte.descent() + texte.ascent()) / 2f
        c.drawText(code, w / 2f, y, texte)

        cache[cleCache] = bmp
        return bmp
    }
}
