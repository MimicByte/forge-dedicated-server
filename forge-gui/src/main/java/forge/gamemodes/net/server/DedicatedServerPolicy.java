package forge.gamemodes.net.server;

/** Dedicated lifecycle callbacks. All methods are called on the GUI dispatcher. */
public interface DedicatedServerPolicy {
    boolean acceptsNewPlayers();
    boolean acceptsLobbyChanges();
    /** Whether a Limited player may submit a completed deck and change ready state. */
    boolean acceptsLimitedDeckUpdates();
    boolean acceptsGameActions();
    /** Whether the current dedicated event accepts draft picks. */
    boolean acceptsDraftActions();
    boolean allowsPlayer(String name);
    int loginFailureLimit();
    int loginFailureWindowSeconds();
    int loginBlockSeconds();
    void connectionsChanged();
    void disconnected(RemoteClient client);
    void reconnected(RemoteClient client);
    boolean wasKicked(RemoteClient client);
}
