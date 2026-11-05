# Patchbay R8 rules. Keep this file minimal; prefer @Keep-free designs.
# kotlinx.serialization: keep generated serializers for @Serializable classes.
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# Fail loudly instead of silently stripping missing references.
-dontnote kotlinx.serialization.**
# SQLCipher uses JNI; keep its native-bound classes.
-keep class net.zetetic.database.** { *; }
