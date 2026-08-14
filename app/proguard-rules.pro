# Règles R8 de NEXUS.
#
# Contexte : le moteur de vision fonctionnait en debug et échouait en release. Les deux variantes
# ne diffèrent que par la minification, ce qui désigne sans ambiguïté une règle de conservation
# manquante. Les bibliothèques de vision reposent massivement sur JNI, la réflexion et des classes
# générées : R8 n'a aucun moyen de voir qu'elles sont utilisées, et les supprime ou les renomme.

# --- JNI ---------------------------------------------------------------------------------------
# Règle canonique, et la plus probablement décisive ici. Le code natif retrouve ses méthodes par
# nom *et par signature* : renommer une classe apparaissant dans les paramètres d'une méthode
# native suffit à rendre la liaison introuvable, même si la méthode elle-même est conservée.
# includedescriptorclasses étend donc la protection aux types des signatures.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# --- MediaPipe Tasks ---------------------------------------------------------------------------
-keep class com.google.mediapipe.** { *; }
-keep interface com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# --- TensorFlow Lite ---------------------------------------------------------------------------
# Le moteur d'inférence sous MediaPipe, y compris ses delegates GPU chargés dynamiquement.
-keep class org.tensorflow.** { *; }
-keep interface org.tensorflow.** { *; }
-dontwarn org.tensorflow.**

# --- AutoValue ---------------------------------------------------------------------------------
# Les options des tâches MediaPipe (HandLandmarkerOptions, BaseOptions...) sont des classes
# abstraites dont l'implémentation est générée sous le nom AutoValue_*. La règle précédente
# visait « les classes étendant com.google.auto.value.AutoValue », ce qui ne correspondait à rien :
# AutoValue est une annotation, pas une classe mère. Aucune classe générée n'était donc protégée.
-keep class **.AutoValue_* { *; }
-keep class com.google.auto.value.** { *; }
-dontwarn com.google.auto.value.**

# --- Protocol Buffers --------------------------------------------------------------------------
# Le fichier .task embarque ses métadonnées en protobuf. Protobuf Lite accède à ses champs par
# réflexion : les supprimer casse la lecture du modèle sans erreur de compilation.
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite$Builder {
    <fields>;
}
-dontwarn com.google.protobuf.**

# --- Dépendances transitives -------------------------------------------------------------------
-keep class com.google.common.util.concurrent.** { *; }
-dontwarn com.google.common.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**
-dontwarn androidx.camera.**

# --- Services ----------------------------------------------------------------------------------
# Instanciés par le système à partir des noms déclarés dans le manifeste.
-keep class com.example.service.StarkAccessibilityService { *; }
-keep class com.example.service.StarkVisionService { *; }

# --- Modèle de données du moteur gestuel -------------------------------------------------------
-keep class com.example.gesture.HandGestureState { *; }
-keep class com.example.gesture.Landmark { *; }

# --- Attributs ---------------------------------------------------------------------------------
# Signature et les annotations d'exécution sont nécessaires à la réflexion de protobuf et
# d'AutoValue ; les numéros de ligne rendent les traces exploitables via mapping-r8.txt.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
