package id.imperial.shop;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ImperialShop's native Vault economy provider.
 *
 * Vault is used as the compatibility API; ImperialShop owns the actual balance data.
 * The default storage is a lightweight YAML file. The storage layer is deliberately
 * isolated so a shared database backend can be added without changing ShopService.
 */
final class ImperialEconomy implements Economy {
    private final ImperialShopPlugin plugin;
    private final Map<UUID, Double> balances = new ConcurrentHashMap<>();
    private final File file;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private volatile boolean enabled;

    ImperialEconomy(ImperialShopPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "economy.yml");
    }

    void load() {
        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            plugin.getLogger().warning("Could not create ImperialShop data directory.");
        }

        if (!file.exists()) {
            enabled = true;
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        var section = config.getConfigurationSection("balances");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    double balance = section.getDouble(key, 0.0);
                    if (Double.isFinite(balance) && balance >= 0) {
                        balances.put(uuid, balance);
                    }
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("Ignoring invalid economy UUID: " + key);
                }
            }
        }

        enabled = true;
        plugin.getLogger().info("Imperial Economy loaded " + balances.size() + " account(s).");
    }

    void startAutosave() {
        long interval = Math.max(100L, plugin.getConfig().getLong("economy.storage.autosave-ticks", 600L));
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::saveIfDirty, interval, interval);
    }

    void shutdown() {
        enabled = false;
        saveNow();
        balances.clear();
    }

    private double normalize(double amount) {
        if (!Double.isFinite(amount)) return 0;
        return Math.round(amount * 100.0) / 100.0;
    }

    private UUID uuid(OfflinePlayer player) {
        return player == null ? null : player.getUniqueId();
    }

    private double balance(UUID uuid) {
        return uuid == null ? 0.0 : Math.max(0.0, balances.getOrDefault(uuid, 0.0));
    }

    private EconomyResponse failure(double amount, UUID uuid, String message) {
        return new EconomyResponse(amount, balance(uuid),
                EconomyResponse.ResponseType.FAILURE, message);
    }

    private EconomyResponse success(double amount, UUID uuid, double newBalance) {
        return new EconomyResponse(amount, newBalance,
                EconomyResponse.ResponseType.SUCCESS, null);
    }

    private EconomyResponse unsupported() {
        return new EconomyResponse(0, 0,
                EconomyResponse.ResponseType.NOT_IMPLEMENTED,
                "Imperial Economy does not support banks.");
    }

    private void markDirty() {
        dirty.set(true);
    }

    private void saveIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            saveSnapshot();
        }
    }

    private void saveNow() {
        dirty.set(false);
        saveSnapshot();
    }

    private void saveSnapshot() {
        final Map<UUID, Double> snapshot = Map.copyOf(balances);
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<UUID, Double> entry : snapshot.entrySet()) {
            config.set("balances." + entry.getKey(), entry.getValue());
        }

        try {
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            config.save(temp);
            if (file.exists() && !file.delete()) {
                plugin.getLogger().warning("Could not replace old economy.yml.");
                return;
            }
            if (!temp.renameTo(file)) {
                plugin.getLogger().warning("Could not finalize economy.yml.");
            }
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save economy.yml: " + exception.getMessage());
            dirty.set(true);
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String getName() {
        return "ImperialEconomy";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 2;
    }

    @Override
    public String format(double amount) {
        return String.format(Locale.US, "Rp %,.2f", amount).replace(".00", "");
    }

    @Override
    public String currencyNamePlural() {
        return plugin.getConfig().getString("economy.currency-name-plural", "Rupiah");
    }

    @Override
    public String currencyNameSingular() {
        return plugin.getConfig().getString("economy.currency-name-singular", "Rupiah");
    }

    @Override
    @Deprecated
    public boolean hasAccount(String playerName) {
        return hasAccount(plugin.getServer().getOfflinePlayer(playerName));
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return player != null;
    }

    @Override
    @Deprecated
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    @Deprecated
    public double getBalance(String playerName) {
        return getBalance(plugin.getServer().getOfflinePlayer(playerName));
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return balance(uuid(player));
    }

    @Override
    @Deprecated
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    @Deprecated
    public boolean has(String playerName, double amount) {
        return has(plugin.getServer().getOfflinePlayer(playerName), amount);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return amount >= 0 && getBalance(player) >= amount;
    }

    @Override
    @Deprecated
    public boolean has(String playerName, String worldName, double amount) {
        return has(plugin.getServer().getOfflinePlayer(playerName), worldName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    @Deprecated
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdrawPlayer(plugin.getServer().getOfflinePlayer(playerName), amount);
    }

    @Override
    public synchronized EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        UUID uuid = uuid(player);
        if (uuid == null) return failure(amount, null, "Invalid player.");
        amount = normalize(amount);
        if (amount < 0) return failure(amount, uuid, "Amount must not be negative.");
        double current = balance(uuid);
        if (current < amount) return failure(amount, uuid, "Insufficient funds.");
        double next = normalize(current - amount);
        balances.put(uuid, next);
        markDirty();
        return success(amount, uuid, next);
    }

    @Override
    @Deprecated
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    @Deprecated
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return depositPlayer(plugin.getServer().getOfflinePlayer(playerName), amount);
    }

    @Override
    public synchronized EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        UUID uuid = uuid(player);
        if (uuid == null) return failure(amount, null, "Invalid player.");
        amount = normalize(amount);
        if (amount < 0) return failure(amount, uuid, "Amount must not be negative.");
        double next = normalize(balance(uuid) + amount);
        balances.put(uuid, next);
        markDirty();
        return success(amount, uuid, next);
    }

    @Override
    @Deprecated
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    @Deprecated
    public EconomyResponse createBank(String name, String player) {
        return unsupported();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return unsupported();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return unsupported();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return unsupported();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return unsupported();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return unsupported();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return unsupported();
    }

    @Override
    @Deprecated
    public EconomyResponse isBankOwner(String name, String playerName) {
        return unsupported();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return unsupported();
    }

    @Override
    @Deprecated
    public EconomyResponse isBankMember(String name, String playerName) {
        return unsupported();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return unsupported();
    }

    @Override
    public List<String> getBanks() {
        return Collections.emptyList();
    }

    @Override
    @Deprecated
    public boolean createPlayerAccount(String playerName) {
        return createPlayerAccount(plugin.getServer().getOfflinePlayer(playerName));
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        if (player == null) return false;
        balances.putIfAbsent(player.getUniqueId(), 0.0);
        return true;
    }

    @Override
    @Deprecated
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }
}
