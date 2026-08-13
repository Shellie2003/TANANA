# Règles R8 du Stark HUD Launcher.
#
# La minification est active en release. Les bibliothèques de vision par ordinateur reposent
# massivement sur JNI et la réflexion : leurs symboles doivent être préservés, faute de quoi
# l'application compile et s'installe mais échoue à charger le modèle à l'exécution.

# --- MediaPipe Tasks ---
# Les classes de tâches et les protos générés sont résolus par nom depuis le code natif.
-keep class com.google.mediapipe.** { *; }
-keep interface com.google.mediapipe.** { *; }
-keepclassmembers class com.google.mediapipe.** {
    native <methods>;
}
-dontwarn com.google.mediapipe.**

# --- TensorFlow Lite (moteur d'inférence sous MediaPipe) ---
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-dontwarn org.tensorflow.lite.**

# --- Protocol Buffers et AutoValue ---
# Les options des tâches MediaPipe sont des classes AutoValue instanciées par réflexion.
-keep class com.google.protobuf.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.auto.value.AutoValue { *; }
-dontwarn com.google.protobuf.**
-dontwarn com.google.auto.value.**
-dontwarn javax.lang.model.**

# --- CameraX ---
-dontwarn androidx.camera.**

# --- Service d'accessibilité ---
# Instancié par le système à partir du nom déclaré dans le manifeste.
-keep class com.example.service.StarkAccessibilityService { *; }

# --- Modèle de données du moteur gestuel ---
# Conservé pour que les traces de plantage restent lisibles sur le chemin critique.
-keep class com.example.gesture.HandGestureState { *; }
-keep class com.example.gesture.Landmark { *; }

# --- Diagnostic ---
# Conserve les numéros de ligne dans les traces tout en masquant les noms de fichiers source.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
