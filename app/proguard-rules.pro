-optimizationpasses 5
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep public class statusbar.lyric.hook.MainHook {
    public <init>();
}
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep class statusbar.lyric.tools.ActivityTools { isHook(); }
-dontskipnonpubliclibraryclassmembers
-allowaccessmodification
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*,!code/allocation/variable
-repackageclasses ztlgc
-obfuscationdictionary proguard-dic-6.txt
-classobfuscationdictionary proguard-dic-6.txt
-packageobfuscationdictionary proguard-dic-6.txt
-dontwarn android.os.ServiceManager