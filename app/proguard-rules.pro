# 自用调试版默认不混淆；发布构建保留以下规则以防反射失效
-keep class com.aixiaohuadb.** { *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
