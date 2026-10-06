# 保留 JNI 入口（RDP 引擎通过 JNI 调用）
-keepclasseswithmembernames class * { native <methods>; }
