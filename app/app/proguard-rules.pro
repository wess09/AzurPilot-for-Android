# R8 规则
#
# 这个应用有两类东西 R8 的可达性分析看不见，改名或裁掉都只在设备上炸，编译期一声不吭：
#   1. native 按字面名 FindClass / GetStaticMethodID 的 upcall 目标
#   2. 特权进程用 app_process 按类名加载的入口（与 app 不是同一个进程）
# 每一条都注明是哪一种，删之前先确认对应的调用方也没了

# 崩溃日志要能对得上号：CrashHandler 落盘的栈是给人看的，行号丢了就只剩类名
# 出包后记得留 build/outputs/mapping/<variant>/mapping.txt
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# ── 1. native upcall ──
# bridge.cpp 里 kNativeBridgeClass / kDriverClass 是字面量，
# bridge_input.cpp 按 "touchDown" "(IIII)Z" 这类签名取 methodID
-keep class com.azurpilot.ghio.bridge.NativeBridgeLib { *; }
-keep class com.azurpilot.ghio.bridge.DriverClass { *; }

# ── 2. 特权进程的入口 ──
# app_process --starter-class / --class 按名字加载；Shizuku 那条走 ComponentName
-keep class com.azurpilot.ghio.remote.RemoteServiceImpl { *; }
-keep class com.azurpilot.ghio.root.** { *; }
# 隐藏 API 的反射壳；反射目标是 framework，但这条路只在特权进程里跑，不值得赌
-keep class com.azurpilot.ghio.third.** { *; }

# AIDL：app 与特权进程各跑一份同样的 dex，descriptor 是字面量，
# 但 Stub/Proxy 被裁掉过一次就再也连不上，成本低于风险
-keep class com.azurpilot.ghio.RemoteService** { *; }
-keep class com.azurpilot.ghio.IRunnerCallback** { *; }
-keep class com.azurpilot.ghio.ITouchEventCallback** { *; }

# hidden-api 是 compileOnly，运行时由 framework 提供，包里没有
-dontwarn android.**
-dontwarn com.android.internal.**


# ── kotlinx.serialization ──
# 生成的 $$serializer 与 Companion.serializer() 没有静态调用点
-keepclassmembers class com.azurpilot.ghio.** {
    *** Companion;
}
-keepclasseswithmembers class com.azurpilot.ghio.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.azurpilot.ghio.**$$serializer { *; }

# ── Room / WorkManager ──
# Room 2.2.5 只保留数据库实现类名；WorkManager 启动时仍会反射调用
# WorkDatabase_Impl 的无参构造器。没有这条，R8 会裁掉构造器并在
# InitializationProvider 阶段直接闪退。
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}

# WorkManager 从数据库读取类名，反射构造 InputMerger。
-keep class * extends androidx.work.InputMerger { <init>(); }

# Glance 按接收器类名构造实例，并把类名作为组件映射和会话键。
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { <init>(); }
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { <init>(); }

# ── 落盘的 enum 常量名 ──
# AppSettings 以 name 存进 DataStore，回读走 valueOf；RunLogKind 按 name 进会话日志文件。
# 改名不会报错，只会让 valueOf 抛异常后静默回落到默认值——用户的设置一次性全丢
-keepclassmembers enum com.azurpilot.ghio.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# OCR JNI 先解包 ModelWrapper，再调用 C API；句柄字段和 LiteRT 钉版一起维护。
-keep class com.google.ai.edge.litert.JniHandle { *; }
# 错误和缓冲区描述由 LiteRT JNI 按字面类名构造；错误分支同样必须保留。
-keep class com.google.ai.edge.litert.** { *; }
-keep class com.azurpilot.ghio.ocr.OcrNative { *; }
-keep class com.azurpilot.ghio.ocr.OcrHiaiNative { *; }
