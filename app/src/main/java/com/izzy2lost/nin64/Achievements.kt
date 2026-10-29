package com.izzy2lost.nin64

import android.content.Context
import android.content.SharedPreferences
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

/** RetroAchievements: the stored account, unlock popups and the list of a game's achievements. */
object Achievements {
    const val SIGN_UP_URL = "https://retroachievements.org/createaccount.php"
    private const val SEP = '\u001f'

    private const val PREF_USER = "cheevos_user"
    private const val PREF_TOKEN = "cheevos_token"
    private const val PREF_DISPLAY_NAME = "cheevos_display_name"
    private const val PREF_ENABLED = "cheevos_enabled"
    private const val PREF_NOTIFICATIONS = "cheevos_notifications"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(DisplayOptionsRepository.PREFS_NAME, Context.MODE_PRIVATE)

    // The token from sign-in stands for the password, which is never kept.
    fun user(context: Context) = prefs(context).getString(PREF_USER, "").orEmpty()
    fun token(context: Context) = prefs(context).getString(PREF_TOKEN, "").orEmpty()
    fun displayName(context: Context) =
        prefs(context).getString(PREF_DISPLAY_NAME, "").orEmpty().ifEmpty { user(context) }
    fun isSignedIn(context: Context) = user(context).isNotEmpty() && token(context).isNotEmpty()
    fun isEnabled(context: Context) = prefs(context).getBoolean(PREF_ENABLED, true)
    fun notificationsEnabled(context: Context) = prefs(context).getBoolean(PREF_NOTIFICATIONS, true)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(PREF_ENABLED, enabled).apply()

    fun setNotificationsEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(PREF_NOTIFICATIONS, enabled).apply()

    fun signIn(context: Context, user: String, token: String, displayName: String) {
        prefs(context).edit()
            .putString(PREF_USER, user)
            .putString(PREF_TOKEN, token)
            .putString(PREF_DISPLAY_NAME, displayName)
            .apply()
    }

    fun signOut(context: Context) {
        prefs(context).edit()
            .remove(PREF_USER)
            .remove(PREF_TOKEN)
            .remove(PREF_DISPLAY_NAME)
            .apply()
    }

    /** Starts achievements for the game just booted, if the player wants them. Emulation thread only. */
    fun beginForLoadedGame(context: Context) {
        if (isEnabled(context) && isSignedIn(context)) {
            NativeBridge.cheevosBegin(user(context), token(context))
        }
    }

    class Row(fields: List<String>) {
        val unlocked = fields.getOrNull(0) == "1"
        val unsupported = fields.getOrNull(1) == "1"
        val points = fields.getOrNull(2)?.toIntOrNull() ?: 0
        val badge = fields.getOrNull(if (unlocked) 3 else 4).orEmpty().ifEmpty { fields.getOrNull(3).orEmpty() }
        val title = fields.getOrNull(5).orEmpty()
        val description = fields.getOrNull(6).orEmpty()
        val progress = fields.getOrNull(7).orEmpty()
    }

    class Snapshot(raw: Array<String>) {
        private val head = raw.firstOrNull()?.split(SEP).orEmpty()
        val state = head.getOrNull(0) ?: "off"
        val game = head.getOrNull(1).orEmpty()
        val gameBadge = head.getOrNull(2).orEmpty()
        val unlocked = head.getOrNull(3)?.toIntOrNull() ?: 0
        val total = head.getOrNull(4)?.toIntOrNull() ?: 0
        val points = head.getOrNull(5)?.toIntOrNull() ?: 0
        val pointsTotal = head.getOrNull(6)?.toIntOrNull() ?: 0
        // Earned first, then the rest; the server's order within each.
        val rows = raw.drop(1).map { Row(it.split(SEP)) }.sortedBy { !it.unlocked }
    }

    /** The in-game list, or why there is none. */
    fun showList(activity: AppCompatActivity) {
        val snap = Snapshot(NativeBridge.cheevosList())
        val problem = when {
            !isSignedIn(activity) -> R.string.cheevos_not_signed_in
            !isEnabled(activity) -> R.string.cheevos_disabled
            snap.state == "off" -> R.string.cheevos_restart_needed
            snap.state == "signed_out" -> R.string.cheevos_sign_in_failed
            snap.state == "signing_in" || snap.state == "loading" -> R.string.cheevos_loading
            snap.state == "signed_in" -> R.string.cheevos_load_failed
            snap.state == "no_set" -> R.string.cheevos_no_set
            snap.state == "empty_set" || snap.rows.isEmpty() -> R.string.cheevos_empty_set
            else -> 0
        }
        if (problem != 0) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.menu_achievements)
                .setIcon(R.drawable.ic_trophy)
                .setMessage(problem)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val content = LayoutInflater.from(activity).inflate(R.layout.dialog_achievements, null, false)
        content.findViewById<ImageView>(R.id.cheevosGameBadge).load(snap.gameBadge.ifEmpty { null })
        content.findViewById<TextView>(R.id.cheevosGameTitle).text = snap.game
        content.findViewById<TextView>(R.id.cheevosSummary).text =
            activity.getString(R.string.cheevos_summary, snap.unlocked, snap.total, snap.points, snap.pointsTotal)
        content.findViewById<LinearProgressIndicator>(R.id.cheevosProgress).apply {
            max = snap.total.coerceAtLeast(1)
            progress = snap.unlocked
        }
        content.findViewById<TextView>(R.id.cheevosUser).text =
            activity.getString(R.string.cheevos_playing_as, displayName(activity))
        content.findViewById<RecyclerView>(R.id.cheevosList).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = RowAdapter(snap.rows)
            // The dialog grows with the list up to most of the screen, then scrolls.
            layoutParams.height = (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
        }
        MaterialAlertDialogBuilder(activity)
            .setView(content)
            .setPositiveButton(R.string.cheevos_close, null)
            .show()
    }

    private class RowAdapter(val rows: List<Row>) : RecyclerView.Adapter<RowAdapter.Holder>() {
        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val badge: ImageView = v.findViewById(R.id.achBadge)
            val title: TextView = v.findViewById(R.id.achTitle)
            val description: TextView = v.findViewById(R.id.achDescription)
            val note: TextView = v.findViewById(R.id.achNote)
            val points: TextView = v.findViewById(R.id.achPoints)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_achievement, parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val r = rows[position]
            val ctx = h.itemView.context
            h.badge.load(r.badge.ifEmpty { null })
            // Locked badges come greyed from the server; a missing one is greyed here.
            h.badge.colorFilter = if (r.unlocked) null else ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            h.badge.alpha = if (r.unlocked) 1f else 0.6f
            h.title.text = r.title
            h.description.text = r.description
            h.points.text = ctx.getString(R.string.cheevos_points, r.points)
            val note = when {
                r.unlocked -> ctx.getString(R.string.cheevos_unlocked)
                r.unsupported -> ctx.getString(R.string.cheevos_unsupported)
                r.progress.isNotEmpty() -> r.progress
                else -> ""
            }
            h.note.text = note
            h.note.visibility = if (note.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    /** Popups over the game, one at a time, each for a few seconds. */
    class Popups(private val activity: AppCompatActivity, private val root: FrameLayout) {
        private val queue = ArrayDeque<Array<String>>()
        private val handler = Handler(Looper.getMainLooper())
        private var showing: View? = null

        /** Takes what the native side has queued; call a few times a second. */
        fun poll() {
            val notify = notificationsEnabled(activity)
            for (m in NativeBridge.cheevosMessages()) {
                val f = m.split(SEP)
                // With notifications off only problems get through: those need the player.
                if (!notify && f.getOrNull(0) != "problem") continue
                // "Achievements loaded": say how far along the player is instead.
                if (f.getOrNull(0) == "info" && f.getOrNull(2) == "achievements active") {
                    val s = Snapshot(NativeBridge.cheevosList())
                    val loaded = activity.getString(R.string.cheevos_loaded_detail, s.unlocked, s.total)
                    queue.addLast(arrayOf("info", f.getOrNull(1).orEmpty(), loaded, "0", s.gameBadge))
                    continue
                }
                queue.addLast(f.toTypedArray())
            }
            if (showing == null) next()
        }

        private fun next() {
            val f = queue.removeFirstOrNull() ?: return
            val kind = f.getOrNull(0)
            val v = LayoutInflater.from(activity).inflate(R.layout.view_achievement_popup, root, false)
            val badge = v.findViewById<ImageView>(R.id.popupBadge)
            val points = f.getOrNull(3)?.toIntOrNull() ?: 0
            v.findViewById<TextView>(R.id.popupHeader).text =
                if (kind == "unlock") activity.getString(R.string.cheevos_popup_unlocked, points)
                else activity.getString(R.string.cheevos_popup_info)
            v.findViewById<TextView>(R.id.popupTitle).text = f.getOrNull(1).orEmpty()
            val detail = f.getOrNull(2).orEmpty()
            v.findViewById<TextView>(R.id.popupDetail).apply {
                text = detail
                visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
            }
            val url = f.getOrNull(4).orEmpty()
            if (url.isNotEmpty()) {
                badge.load(url)
            } else {
                badge.setImageResource(R.drawable.ic_trophy)
                badge.setColorFilter(0xFFF9A825.toInt())
            }
            root.addView(v)
            showing = v
            v.translationY = -200f
            v.alpha = 0f
            v.animate().translationY(0f).alpha(1f).setDuration(250).start()
            handler.postDelayed({
                v.animate().translationY(-200f).alpha(0f).setDuration(250).withEndAction {
                    root.removeView(v)
                    showing = null
                    next()
                }.start()
            }, if (kind == "unlock") 4500L else 3000L)
        }

        /** Drops anything pending, for when the game ends. */
        fun clear() {
            queue.clear()
            handler.removeCallbacksAndMessages(null)
            showing?.let(root::removeView)
            showing = null
        }
    }
}
