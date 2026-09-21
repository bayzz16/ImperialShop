package id.imperial.shop;

import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class TransactionHistory {
    private static final int RECENT_LIMIT = 100;
    private final ImperialShopPlugin plugin;
    private final Path file;
    private final Deque<Record> recent = new ArrayDeque<>();
    private final Map<UUID, Stats> stats = new ConcurrentHashMap<>();
    private final AtomicLong totalTransactions = new AtomicLong();

    TransactionHistory(ImperialShopPlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("transactions.csv");
        load();
    }

    private void load() {
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                Files.writeString(file, "timestamp,uuid,player,type,material,amount,total\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE);
                return;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                String[] p = lines.get(i).split(",", -1);
                if (p.length != 6) continue;
                try {
                    UUID uuid = UUID.fromString(p[1]);
                    int amount = Integer.parseInt(p[4]);
                    double total = Double.parseDouble(p[5]);
                    addStats(uuid, amount, total);
                    addRecent(new Record(p[0], uuid, p[2], p[3], p[3].equals("BUY") ? p[3] : p[3], amount, total));
                } catch (RuntimeException ignored) {
                }
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not load transaction history: " + ex.getMessage());
        }
    }

    void record(Player player, String type, org.bukkit.Material material, int amount, double total) {
        if (amount < 1 || !Double.isFinite(total) || total < 0) return;
        String timestamp = Instant.now().toString();
        Record record = new Record(timestamp, player.getUniqueId(), player.getName(), type, material.name(), amount, total);
        addStats(record.uuid(), amount, total);
        addRecent(record);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> append(record));
    }

    private synchronized void append(Record record) {
        try {
            Files.createDirectories(file.getParent());
            String line = String.join(",",
                    record.timestamp(), record.uuid().toString(), csv(record.player()),
                    record.type(), record.material(), String.valueOf(record.amount()),
                    Double.toString(record.total())) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not write transaction history: " + ex.getMessage());
        }
    }

    private String csv(String value) {
        return value.replace(",", "_").replace("\n", "_").replace("\r", "_");
    }

    private void addStats(UUID uuid, int amount, double total) {
        stats.compute(uuid, (key, old) -> {
            Stats value = old == null ? new Stats() : old;
            value.transactions++;
            value.items += amount;
            value.money += total;
            return value;
        });
        totalTransactions.incrementAndGet();
    }

    private synchronized void addRecent(Record record) {
        recent.addLast(record);
        while (recent.size() > RECENT_LIMIT) recent.removeFirst();
    }

    long totalTransactions() {
        return totalTransactions.get();
    }

    Stats stats(UUID uuid) {
        Stats value = stats.get(uuid);
        return value == null ? new Stats() : value.copy();
    }

    List<Record> recent(int limit) {
        int max = Math.max(1, Math.min(RECENT_LIMIT, limit));
        synchronized (this) {
            List<Record> result = new ArrayList<>(recent);
            Collections.reverse(result);
            return result.subList(0, Math.min(max, result.size()));
        }
    }

    void shutdown() {
        // Writes are scheduled individually; no persistent executor needs closing.
    }

    static final class Stats {
        long transactions;
        long items;
        double money;

        Stats copy() {
            Stats copy = new Stats();
            copy.transactions = transactions;
            copy.items = items;
            copy.money = money;
            return copy;
        }
    }

    record Record(String timestamp, UUID uuid, String player, String type, String material, int amount, double total) {}
}
