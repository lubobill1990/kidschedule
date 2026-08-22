# kotlinx-serialization:序列化器经反射查找,DTO 相关类必须保留
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class app.kidschedule.**$$serializer { *; }
-keepclassmembers class app.kidschedule.** { *** Companion; }
-keepclasseswithmembers class app.kidschedule.** { kotlinx.serialization.KSerializer serializer(...); }

# Glance 的 actionRunCallback 按类名反射实例化
-keep class * implements androidx.glance.appwidget.action.ActionCallback

# Ktor/OkHttp 可选依赖
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
