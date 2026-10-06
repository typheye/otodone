package com.otodone.app

import android.app.Application
import com.otodone.app.data.RdpHostRepository

/** 应用入口：只做依赖装配，避免全局单例散落。 */
class OtoDoneApp : Application() {

    val hostRepository: RdpHostRepository by lazy { RdpHostRepository(this) }
}
