package demo;

/**
 * Point d'entrée Java de l'exemple : appelle l'objet Kotlin du module — la
 * compilation mixte est le cœur de la démonstration (compileKotlin précède
 * compileJava, les deux langages partagent le répertoire de classes).
 */
public final class Principal {

    private Principal() {
    }

    public static void main(String[] args) {
        System.out.println(Calculs.INSTANCE.saluer("Forge"));
        System.out.println("doubled=" + Calculs.INSTANCE.doubler(21));
    }
}
