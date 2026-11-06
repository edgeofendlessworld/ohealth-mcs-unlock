# 保留 Xposed 模块入口类（新版 API 通过 META-INF/xposed/java_init.list 反射实例化）
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-adaptresourcefilecontents META-INF/xposed/java_init.list
-dontwarn io.github.libxposed.annotation.**
