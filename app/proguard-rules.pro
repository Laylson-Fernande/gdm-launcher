# AIDL interfaces and Parcelables of the NWD radio service: the names must be kept.
-keep class com.nwd.** { *; }
-keep class com.bt.** { *; }
-keep class com.acloud.newinterface.MusicBean { *; }

# Readable stack traces ("Último erro" screen and logcat): no class renaming, with line numbers.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
