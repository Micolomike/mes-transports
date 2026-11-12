package fr.micolomike.mestransports

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Petits constructeurs de vues (écrans construits en code, sans XML). */
object Ui {
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun encre(ctx: Context) = ContextCompat.getColor(ctx, R.color.encre)
    fun pale(ctx: Context) = ContextCompat.getColor(ctx, R.color.encre_pale)

    fun colonne(ctx: Context, padding: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, padding)
        setPadding(p, p, p, p)
    }

    fun ligne(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun carte(ctx: Context): LinearLayout = colonne(ctx, 14).apply {
        background = ContextCompat.getDrawable(ctx, R.drawable.carte)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(ctx, 12) }
    }

    fun texte(
        ctx: Context, t: CharSequence, taille: Float = 15f, gras: Boolean = false, pale: Boolean = false,
    ): TextView = TextView(ctx).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, taille)
        setTextColor(if (pale) pale(ctx) else encre(ctx))
        if (gras) setTypeface(typeface, Typeface.BOLD)
    }

    fun titre(ctx: Context, t: String): TextView = texte(ctx, t, 17f, gras = true).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(ctx, 24) }
    }

    fun bouton(ctx: Context, t: String, largeur: Int = ViewGroup.LayoutParams.MATCH_PARENT, action: () -> Unit): Button =
        Button(ctx).apply {
            text = t
            isAllCaps = false
            setTextColor(encre(ctx))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = ContextCompat.getDrawable(ctx, R.drawable.bouton)
            layoutParams = LinearLayout.LayoutParams(largeur, dp(ctx, 48)).apply { topMargin = dp(ctx, 8) }
            setOnClickListener { action() }
        }

    /** Petit bouton carré (↑ ↓ ✕ ↻ ⚙). */
    fun icone(ctx: Context, t: String, action: () -> Unit): Button = bouton(ctx, t, dp(ctx, 44), action).apply {
        setPadding(0, 0, 0, 0)
        (layoutParams as LinearLayout.LayoutParams).apply {
            height = dp(ctx, 44)
            topMargin = 0
            marginStart = dp(ctx, 6)
        }
    }

    fun champ(ctx: Context, indice: String): EditText = EditText(ctx).apply {
        hint = indice
        setTextColor(encre(ctx))
        setHintTextColor(pale(ctx))
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(ctx, 8) }
    }

    fun pastille(ctx: Context, l: Ligne, taille: Int = 30): ImageView = ImageView(ctx).apply {
        setImageBitmap(Pastille.dessiner(ctx, l, taille))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, taille))
            .apply { marginEnd = dp(ctx, 12) }
        adjustViewBounds = true
    }

    /** Vue qui prend la place restante d'une ligne horizontale. */
    fun <T : View> extensible(v: T): T = v.apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
}
