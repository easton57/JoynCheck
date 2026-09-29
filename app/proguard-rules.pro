# Shizuku instantiates the bridge user service by reflection, and the AIDL stubs cross processes.
-keep class com.eastonseidel.joyncheck.bridge.** { *; }
-keep class rikka.shizuku.** { *; }
