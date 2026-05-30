# Add project specific ProGuard rules here.
# By default, the ProGuard rules in this file are appended to the default ProGuard
# rules for the Android SDK.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepclassmembers class * {
    @androidx.room.Database *;
    @androidx.room.Dao *;
    @androidx.room.Entity *;
}
