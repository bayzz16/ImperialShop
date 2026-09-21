package id.imperial.shop;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class FavoritesStore {
    private final ImperialShopPlugin plugin;
    private final File file;
    private final Map<UUID, Set<Material>> favorites = new HashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ImperialShop-FavoritesWriter");
        thread.setDaemon(true);
        return thread;
    });

    FavoritesStore(ImperialShopPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "favorites.yml");
        load();
    }

    private synchronized void load() {
        if (!file.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        for (String rawUuid : config.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(rawUuid);
                Set<Material> set = EnumSet.noneOf(Material.class);
                for (String raw : config.getStringList(rawUuid)) {
                    Material material = Material.matchMaterial(raw);
                    if (material != null) set.add(material);
                }
                favorites.put(uuid, set);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    synchronized boolean isFavorite(Player player, Material material) {
        return favorites.getOrDefault(player.getUniqueId(), Collections.emptySet()).contains(material);
    }

    synchronized boolean toggle(Player player, Material material) {
        Set<Material> set = favorites.computeIfAbsent(player.getUniqueId(), ignored -> EnumSet.noneOf(Material.class));
        boolean added;
        if (set.contains(material)) {
            set.remove(material);
            added = false;
        } else {
            set.add(material);
            added = true;
        }
        saveAsync();
        return added;
    }

    synchronized List<Material> get(Player player) {
        return favorites.getOrDefault(player.getUniqueId(), Collections.emptySet()).stream()
                .filter(plugin::isConfiguredMaterial)
                .sorted(Comparator.comparing(plugin::displayNameForSort))
                .toList();
    }

    private void saveAsync() {
        Map<UUID, List<String>> snapshot = new HashMap<>();
        for (Map.Entry<UUID, Set<Material>> entry : favorites.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().stream().map(Material::name).sorted().toList());
        }
        writer.execute(() -> {
            YamlConfiguration config = new YamlConfiguration();
            for (Map.Entry<UUID, List<String>> entry : snapshot.entrySet()) {
                config.set(entry.getKey().toString(), entry.getValue());
            }
            try {
                file.getParentFile().mkdirs();
                config.save(file);
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not save favorites: " + ex.getMessage());
            }
        });
    }

    void shutdown() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }
}
