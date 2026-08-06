# BeHoly ProGuard / R8 规则
#
# 本项目零额外第三方依赖(纯 AndroidX + JDK + kotlinx-coroutines),
# 规则主要保留系统组件反射入口、Kotlin object 单例、Parcelable 等。

# 保留行号与源文件名(便于崩溃日志定位)
-keepattributes SourceFile,LineNumberTable

# 保留异常签名(便于崩溃栈)
-keepattributes Exceptions

# 保留注解(R8 默认会保留运行时注解,显式声明更安全)
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,
                RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations,
                AnnotationDefault

# org.json:Android 内置库,R8 可能误报
-dontwarn org.json.**

# 保留 Kotlin object 单例的 INSTANCE 字段(object 编译为持有 INSTANCE 的类)
-keepclassmembers class com.example.beholy.** {
    public static ** INSTANCE;
}

# 保留系统组件(系统通过反射实例化 Activity/Service/Application/Receiver)
# R8 默认会保留 manifest 声明的组件,但显式声明更安全
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.app.Application
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.accessibilityservice.AccessibilityService
-keep public class * extends android.app.admin.DeviceAdminReceiver

# 保留 Parcelable CREATOR(标准 Android 规则)
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# 保留 enum 的 values/valueOf(部分库反射访问)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# kotlinx-coroutines:R8 通常能正确处理,显式保留重要类防患于未然
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }

# Room:保留生成的数据库实现类与 Entity(KSP 生成的 DAO 实现需保留)
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
