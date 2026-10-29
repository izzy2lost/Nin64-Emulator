# JNI exports in nin64_core use the concrete NativeBridge class and method names.
-keep class com.izzy2lost.nin64.NativeBridge { *; }

# RetroAchievements' HTTP: FindClass + GetStaticMethodID from native code
# (cpp/cheevos/cheevos_session.cpp), so R8 sees no caller and would remove or rename it.
-keep class com.izzy2lost.nin64.CheevosHttp {
    public static byte[] perform(java.lang.String, java.lang.String, java.lang.String, java.lang.String);
}


# Room (pulled in through WorkManager) creates each generated *_Impl database by
# reflection, but its bundled rule keeps the class without its constructor, which
# AGP 9's strict full-mode R8 then removes.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
