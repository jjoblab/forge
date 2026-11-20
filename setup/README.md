# setup/ — installation et mise à jour du moteur

Logique d'installation du moteur, **testable sans interface** : ce module ne connaît ni Android,
ni Compose — uniquement des chemins, des fichiers et des vérifications.

Contenu prévu :

- lecture et validation du manifeste de composants (identifiant, version, taille, SHA-256, URL,
  ABI, version minimale de l'application, dépendances entre composants) ;
- vérification d'authenticité du manifeste (signature, clé publique embarquée dans l'application) ;
- téléchargement reprenable après coupure, avec progression et annulation ;
- vérification d'intégrité SHA-256 de chaque composant **avant** toute installation ;
- installation atomique (répertoire temporaire puis renommage) avec retour arrière ;
- mise à jour, réparation, désinstallation ;
- installation hors ligne depuis un fichier local ;
- extraction d'archives protégée contre la traversée de chemin (zip-slip).

L'assistant visuel (écrans, avertissements données mobiles, espace disque) vit dans `app/` et
délègue à ce module.

Ce module est livré depuis l'étape 3 de la Phase 4 : manifeste signé ECDSA P-256, téléchargeur
reprenable (HTTP `Range`), extraction anti-zip-slip plafonnée, installation atomique, mise à
jour/réparation/désinstallation, plan d'installation et installation hors ligne — le tout testé
avec de vrais serveurs HTTP locaux.

> Le parseur/écriteur JSON historique de ce module vit désormais dans le module partagé
> [`json/`](../json/README.md) (extrait à l'étape 8, quand le moteur a eu besoin de lire
> `forge.json` sans dépendre de l'installateur).
