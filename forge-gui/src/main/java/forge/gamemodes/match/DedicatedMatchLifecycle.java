package forge.gamemodes.match;

/** Lifecycle notifications for a dedicated match. */
public interface DedicatedMatchLifecycle {
    /** A game is about to be created, including a later game in the same match. */
    void gameStarting();

    /** The game is safe for remote clients to inspect and act on. */
    void gamePrepared();

    /** The game has ended and players may make their next-match decision. */
    void gameFinished();
}
