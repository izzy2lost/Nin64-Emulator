package com.izzy2lost.nin64

import android.view.Surface

object NativeBridge {
    init {
        System.loadLibrary("nin64_core")
    }

    external fun bootRomForPlay(rootPath: String, romPath: String, saveKey: String): String
    external fun flushInGameSave(): String
    external fun runFrame(ops: Int)
    external fun getFrameWidth(): Int
    external fun getFrameHeight(): Int
    external fun setControllerState(buttonMask: Int, stickX: Int, stickY: Int)
    external fun saveState(path: String): String
    external fun loadState(path: String): String
    external fun setSurface(surface: Surface?, width: Int, height: Int)
    external fun clearSurface()
    external fun shutdownSession()
    external fun setOption(key: String, value: String)
    external fun setFastForwardEnabled(enabled: Boolean)
    external fun setCheats(codeLines: Array<String>)

    // RetroAchievements
    /** After [bootRomForPlay], on the emulation thread: signs in and loads the game's achievements in the background. */
    external fun cheevosBegin(user: String, token: String)
    /** Messages to show: kind, title, detail, points, badge URL, separated by \u001f. */
    external fun cheevosMessages(): Array<String>
    /** Header row (state, game, game badge, unlocked, total, points, total points), then one row per achievement. */
    external fun cheevosList(): Array<String>
    /** Blocking sign-in for Settings: {user, token, display name, score}, or {"", error}. */
    external fun cheevosLogin(user: String, password: String): Array<String>
}
