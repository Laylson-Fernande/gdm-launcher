# Interfaces AIDL e Parcelables dos serviços da NWD: os nomes precisam ser mantidos.
-keep class com.nwd.** { *; }
-keep class com.bt.** { *; }
# Faixa do player da NWD no broadcast com.android.music.metachanged ("o que está tocando").
-keep class com.acloud.newinterface.MusicBean { *; }

# Pilhas de erro legíveis no logcat: sem renomear classes, com nº de linha.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
