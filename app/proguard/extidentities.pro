# Angus Mail (Jakarta Mail) — used by the external-identities SMTP send path.
# R8 full mode: keep MIME/activation machinery that relies on reflection & service loading.

-dontwarn java.beans.**
-dontwarn javax.security.auth.x500.**
-dontwarn javax.security.sasl.**
-dontwarn org.jboss.security.**
-dontwarn java.awt.Image
-dontwarn java.awt.Toolkit
-dontwarn javax.security.auth.callback.NameCallback
-dontwarn org.graalvm.nativeimage.hosted.Feature$BeforeAnalysisAccess
-dontwarn org.graalvm.nativeimage.hosted.Feature$IsInConfigurationAccess
-dontwarn org.graalvm.nativeimage.hosted.Feature
-dontwarn org.graalvm.nativeimage.hosted.RuntimeReflection

# Service loader style lookups for protocol providers / handlers
-keep class org.eclipse.angus.** { *; }
-keep class jakarta.activation.** { *; }
-keep class com.sun.activation.** { *; }

# Keep mail handler/protocol classes referenced via Session properties
-keep class * extends jakarta.mail.Service { *; }
-keep class * extends jakarta.activation.DataHandler { *; }
-keep class * implements jakarta.activation.CommandObject { *; }
