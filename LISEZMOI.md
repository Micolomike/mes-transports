# Mes transports

Widget Android d'écran d'accueil pour le trajet RER E (Emerainville ↔ Haussmann-Saint-Lazare) et la ligne 14 (Saint-Lazare ↔ Saint-Ouen). Portage du widget Windows « Tableau de bord » : mêmes appels à l'API PRIM d'Île-de-France Mobilités, mêmes filtres d'incidents, mêmes niveaux de couleur.

## Ce qu'affiche le widget

- Pastille de couleur : vert (à l'heure), orange (retard de moins de 10 min ou incident), rouge (retard de 10 min ou plus, train supprimé, incident bloquant), gris (données indisponibles).
- L'incident principal en cours sur le RER E ou la ligne 14 (accès en gare et gares hors trajet ignorés).
- Les 3 prochains départs dans chaque sens : Emerainville → St-Lazare et St-Lazare → Tournan, avec le retard (+3) ou la suppression. Le matin, le sens vers Paris est en premier ; l'après-midi, le retour.
- Le bouton ↻ actualise immédiatement ; un appui ailleurs ouvre l'appli.

## Mises à jour

- Automatiques toutes les 15 minutes (minimum imposé par Android pour une tâche de fond).
- Par défaut, seulement aux heures de trajet : lundi à vendredi, 6h00 à 9h15 et 16h00 à 18h15. Réglable dans l'appli.
- Sur Samsung, exclure l'appli de l'optimisation de batterie (bouton dans l'appli), sinon One UI peut retarder les mises à jour.

## Installation

1. GitHub Actions compile l'APK à chaque modification du dossier `android/` et le publie dans **Releases** (`mes-transports.apk`).
2. Sur le téléphone : télécharger `mes-transports.apk`, l'ouvrir, puis Installer.
3. Ouvrir l'appli, coller le jeton PRIM (le même que `Documents\TransportsIDF\config.json` sur le PC, champ `apikey`), Enregistrer.
4. Bouton « Ajouter le widget », ou appui long sur l'écran d'accueil > Widgets > Mes transports.

Le jeton n'est jamais dans le code ni dans le dépôt : il est saisi dans l'appli et reste sur le téléphone.

## Signature de l'APK

Sans clé, chaque compilation est signée avec une clé temporaire différente : il faut désinstaller l'appli avant chaque mise à jour. Pour l'éviter, ajouter dans Settings > Secrets and variables > Actions du dépôt les mêmes secrets que Cadran :

- `KEYSTORE_B64` : le keystore encodé en base64
- `KEYSTORE_PASSWORD` : son mot de passe
- `KEYSTORE_ALIAS` (facultatif) : l'alias de la clé, `cadran` par défaut
