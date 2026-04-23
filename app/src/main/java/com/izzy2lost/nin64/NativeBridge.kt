package com.izzy2lost.nin64

import android.view.Surface

object NativeBridge {
    init {
        System.loadLibrary("nin64_core")
    }

    external fun init(rootPath: String): String
    external fun smokeTest(): String
    external fun bootRom(rootPath: String, romPath: String, stepOps: Int, stepCount: Int): String
    external fun getFrameWidth(): Int
    external fun getFrameHeight(): Int
    external fun copyFrameBufferArgb(): IntArray
    external fun copyFrameBufferArgbInto(buffer: IntArray): Int

    external fun bootRomForPlay(rootPath: String, romPath: String): String
    external fun runFrame(ops: Int)
    external fun getSwapCount(): Int
    external fun setControllerState(buttonMask: Int, stickX: Int, stickY: Int)
    external fun setSurface(surface: Surface?, width: Int, height: Int)
    external fun clearSurface()
    external fun shutdownSession()
}
