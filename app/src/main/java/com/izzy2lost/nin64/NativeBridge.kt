package com.izzy2lost.nin64

import android.view.Surface

object NativeBridge {
    init {
        System.loadLibrary("nin64_core")
    }

    external fun bootRomForPlay(rootPath: String, romPath: String): String
    external fun runFrame(ops: Int)
    external fun getFrameWidth(): Int
    external fun getFrameHeight(): Int
    external fun setControllerState(buttonMask: Int, stickX: Int, stickY: Int)
    external fun setSurface(surface: Surface?, width: Int, height: Int)
    external fun clearSurface()
    external fun shutdownSession()
    external fun setOption(key: String, value: String)
}
