package com.martecyber.plugins.bughunting;

import com.martecyber.ares.plugins.PluginExtensionRegistry;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds every currently-live {@link BugHuntingClient} — the ones bundled directly in this plugin
 * ({@link BugcrowdClient}/{@link YesWeHackClient}, which self-register via their own {@code
 * @PostConstruct} since {@link com.martecyber.ares.plugins.PluginLoader} only auto-discovers
 * implementations of an extension point declared by a DEPENDENCY, not by the plugin that owns the
 * point itself) plus whatever a dependent plugin (ares-plugin-bughunting-hackerone,
 * ares-plugin-bughunting-intigriti) contributes — those ARE auto-registered/unregistered by {@code
 * PluginLoader} as that plugin loads/unloads. {@link BugHuntingSyncJobHandler} reads this list
 * instead of taking a constructor-injected {@code List<BugHuntingClient>}, since Spring's own
 * multi-bean collection injection only sees whatever was already a registered singleton at THIS
 * bean's own construction time — a dependent plugin installed/loaded later would never show up.
 */
public class BugHuntingClientRegistry implements PluginExtensionRegistry<BugHuntingClient> {

    private final List<BugHuntingClient> clients = new CopyOnWriteArrayList<>();

    @Override
    public void register(BugHuntingClient instance) {
        clients.add(instance);
    }

    @Override
    public void unregister(BugHuntingClient instance) {
        clients.remove(instance);
    }

    public List<BugHuntingClient> clients() {
        return List.copyOf(clients);
    }

    public Optional<BugHuntingClient> forPlatform(String platform) {
        return clients.stream().filter(c -> c.platform().equalsIgnoreCase(platform)).findFirst();
    }
}
