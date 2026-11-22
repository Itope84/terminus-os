# Debug builds are shrink-only: unused code and resources go, nothing is renamed or inlined,
# so stack traces stay readable and no mapping file is needed.
-dontobfuscate
-dontoptimize

# WebView bridges are called only from JavaScript; keep them.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep attributes for stack traces and Kotlin metadata.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
