# Keep OpenReadEra JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Room entities and DAOs
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
