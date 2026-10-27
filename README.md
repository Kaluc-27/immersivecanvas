# Immersive Canvas (NeoForge 1.21.11)

Un canevas posable au mur, dessinable pixel par pixel (16/32/64), avec :
crayon, gomme, seau de remplissage, pipette (outil ou clic droit), annuler/rétablir,
couleurs HSV + hex + palette, import d'image (png/jpg/bmp, recadrée au carré),
le dessin est conservé quand on casse/replace le bloc.

## Utilisation
- Recette : 8 bâtons + 1 laine blanche au centre.
- Clic droit sur le canevas posé : ouvre l'éditeur. « Enregistrer » envoie au serveur.

## Build
1. Récupère le wrapper Gradle (gradlew, gradle/wrapper/*) depuis le MDK officiel
   NeoForge 1.21.11 (ModDevGradle) et copie-le ici — ou utilise un Gradle 9.x installé.
2. Vérifie `neo_version` dans gradle.properties (dernière 21.11.x).
3. `./gradlew runClient` pour tester, `./gradlew build` pour le jar (build/libs).

## Si la compilation coince (API 1.21.11 récentes)
- Imports : laisse l'IDE les corriger (packages de BlockEntityRenderState, CameraRenderState, ModelFeatureRenderer).
- `new BlockEntityType<>(CanvasBlockEntity::new, block)` : si refusé, utiliser l'AT/la variante du MDK.
- `DynamicTexture(Supplier<String>, NativeImage)` : adapter au constructeur disponible.
- `NativeImage.setPixel` : doit être en ARGB.
- `ClientPacketDistributor.sendToServer` : sinon `PacketDistributor.sendToServer`.
- `MouseButtonEvent` (event.x(), event.y(), event.button()) dans l'écran.
