package com.otodone.app.rdp

import android.util.Log
import com.freerdp.freerdpcore.services.LibFreeRDP

/**
 * 懒加载 vendored FreeRDP JNI 桥，并记录加载结果。
 *
 * [LibFreeRDP] 的静态初始化会 System.loadLibrary("freerdp-android") 并调用
 * freerdp_get_jni_version()，任何一步失败都会以 Throwable 形式抛出；这里统一
 * 捕获，失败时 App 只显示错误而不是崩溃。
 */
object RdpNative {

    private const val TAG = "RdpNative"

    data class Info(
        val version: String,
        val jniVersion: String,
        val buildRevision: String,
        val hasH264: Boolean,
        val hasCameraRedirection: Boolean,
        val error: String?,
    ) {
        val loaded: Boolean get() = error == null
        val label: String get() = if (loaded) "FreeRDP $version" else "FreeRDP 未加载"
    }

    val info: Info by lazy { load() }

    private fun load(): Info = try {
        val version = LibFreeRDP.getVersion()
        val jniVersion = LibFreeRDP.getJniVersion()
        val revision = runCatching { LibFreeRDP.getBuildRevision() }.getOrDefault("")
        val hasH264 = LibFreeRDP.hasH264Support()
        val hasCamera = LibFreeRDP.hasCameraRedirectionSupport()
        Log.i(
            TAG,
            "FreeRDP native loaded: version=$version jniVersion=$jniVersion revision=$revision h264=$hasH264 camera=$hasCamera",
        )
        Info(version, jniVersion, revision, hasH264, hasCamera, null)
    } catch (t: Throwable) {
        Log.e(TAG, "FreeRDP native load failed", t)
        Info("", "", "", false, false, "${t.javaClass.simpleName}: ${t.message}")
    }
}
