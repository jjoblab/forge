package jo.forge.app;

import jo.forge.setup.download.CancellationToken;
import jo.forge.setup.download.Downloader;
import jo.forge.setup.install.ComponentSource;
import jo.forge.setup.manifest.ComponentManifest;

import java.nio.file.Path;
import java.util.List;

/**
 * Source de composants avec progression GLOBALE : l'installateur télécharge les
 * composants l'un après l'autre et ne rapporte que des octets par composant ; cette
 * source cumule les tailles des composants terminés pour donner à l'interface une
 * progression d'ensemble (octets reçus sur total de l'installation).
 *
 * <p>Classe sans dépendance Android : testée sur JVM comme le reste de la chaîne
 * d'installation. Un composant déjà complet dans le cache (reprise) est compté
 * intégralement dès sa validation.</p>
 *
 * <p>Économie de données (v0.2.9) : les composants servis par le cache local ne
 * sont JAMAIS demandés à la source — le total global annoncé est donc la somme
 * des seuls composants à télécharger, pour que la barre aille au bout.</p>
 */
final class SourceProgressive implements ComponentSource {

    private final ComponentSource source;
    private final Downloader.ProgressListener écouteur;
    private final long totalGlobal;
    private long octetsTerminés;

    /**
     * Crée la source progressive sur TOUT les composants (aucun en cache).
     *
     * @param source      la source réelle (réseau en production).
     * @param composants  les composants qui seront demandés, dans l'ordre.
     * @param écouteur    rapporteur global (octets cumulés, total cumulé) ; peut
     *                    être nul.
     */
    SourceProgressive(ComponentSource source, List<ComponentManifest> composants,
                      Downloader.ProgressListener écouteur) {
        this(source, composants, java.util.Set.of(), écouteur);
    }

    /**
     * Crée la source progressive en excluant du total les composants déjà en cache
     * local (ils ne traversent pas le réseau : l'installateur les lie depuis
     * {@code forge/cache/composants}).
     *
     * @param source      la source réelle (réseau en production).
     * @param composants  les composants du manifeste applicables à l'ABI.
     * @param enCache     identifiants des composants déjà présents dans le cache.
     * @param écouteur    rapporteur global (octets cumulés, total cumulé) ; peut
     *                    être nul.
     */
    SourceProgressive(ComponentSource source, List<ComponentManifest> composants,
                      java.util.Set<String> enCache, Downloader.ProgressListener écouteur) {
        this.source = source;
        this.écouteur = écouteur;
        this.totalGlobal = composants.stream()
                .filter(composant -> !enCache.contains(composant.id()))
                .mapToLong(ComponentManifest::size).sum();
    }

    @Override
    public Path fetch(ComponentManifest component, Downloader.ProgressListener ignoré,
                      CancellationToken cancel) {
        Path archive = source.fetch(component, (reçus, total) -> {
            Downloader.ProgressListener rapporteur = écouteur;
            if (rapporteur != null) {
                rapporteur.onProgress(octetsTerminés + reçus, totalGlobal);
            }
        }, cancel);
        // Composant terminé (téléchargé ou repris du cache) : il compte pour sa
        // taille complète dans la progression globale.
        octetsTerminés += component.size();
        Downloader.ProgressListener rapporteur = écouteur;
        if (rapporteur != null) {
            rapporteur.onProgress(octetsTerminés, totalGlobal);
        }
        return archive;
    }
}
