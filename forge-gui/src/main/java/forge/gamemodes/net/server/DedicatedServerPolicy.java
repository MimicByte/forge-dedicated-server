package forge.gamemodes.net.server;

/** Dedicated lifecycle callbacks. All methods are called on the GUI dispatcher. */
public interface DedicatedServerPolicy {
    boolean acceptsNewPlayers();
    boolean acceptsLobbyChanges();
    boolean acceptsGameActions();
    boolean allowsPlayer(String name);
    int loginFailureLimit();
    int loginFailureWindowSeconds();
    int loginBlockSeconds();
    void connectionsChanged();
    void disconnected(RemoteClient client);
    void reconnected(RemoteClient client);
}
