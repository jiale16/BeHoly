# 默认保留行号信息，便于调试；本项目未开启混淆（release 中 isMinifyEnabled=false）。
-keepattributes SourceFile,LineNumberTable

# 保留 TFLite / ML Kit 相关（如开启混淆再按需补全）
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.mlkit.** { *; }
