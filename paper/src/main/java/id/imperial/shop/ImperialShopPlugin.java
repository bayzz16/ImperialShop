package id.imperial.shop;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Material;
import org.bukkit.ChatColor;

public final class ImperialShopPlugin extends JavaPlugin {
    private Economy economy;
    private ImperialEconomy imperialEconomy;
    private ShopService shop;
    private TransactionHistory history;
    private FavoritesStore favorites;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        getServer().getMessenger().registerIncomingPluginChannel(
                this, VelocityBridgeListener.CHANNEL, new VelocityBridgeListener(this));

        String providerMode = getConfig().getString("economy.provider", "imperial");
        if (providerMode.equalsIgnoreCase("imperial")) {
            imperialEconomy = new ImperialEconomy(this);
            imperialEconomy.load();
            getServer().getServicesManager().register(
                    Economy.class, imperialEconomy, this, ServicePriority.Highest);
            imperialEconomy.startAutosave();
            economy = imperialEconomy;
            getLogger().info("Imperial Economy registered as the Vault economy provider.");
        } else {
            RegisteredServiceProvider<Economy> registration =
                    getServer().getServicesManager().getRegistration(Economy.class);

            if (registration == null || registration.getProvider() == null) {
                getLogger().severe("Vault economy provider not found. Set economy.provider: imperial or install an external Vault economy provider.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            economy = registration.getProvider();
            getLogger().info("Using external Vault economy provider: " + economy.getName());
        }

        history = new TransactionHistory(this);
        favorites = new FavoritesStore(this);
        shop = new ShopService(this, economy);

        if (getCommand("shop") != null) getCommand("shop").setExecutor(new ShopCommand(shop));
        if (getCommand("sell") != null) getCommand("sell").setExecutor(new SellCommand(shop));
        if (getCommand("sellgui") != null) getCommand("sellgui").setExecutor(new SellCommand(shop));
        if (getCommand("sellall") != null) getCommand("sellall").setExecutor(new SellAllCommand(shop));
        if (getCommand("sellhand") != null) getCommand("sellhand").setExecutor(new SellHandCommand(shop));
        if (getCommand("imperialshop") != null) {
            getCommand("imperialshop").setExecutor(new AdminCommand(this, shop));
        }

        getServer().getPluginManager().registerEvents(new ShopListener(shop), this);
        getLogger().info("ImperialShop 2.1.0 enabled for Paper/Purpur 1.21.x.");
    }

    String networkSecret() {
        String configured = getConfig().getString("network.shared-secret", "");
        String environment = System.getenv("IMPERIALSHOP_NETWORK_SECRET");
        if (environment != null && !environment.isBlank()) return environment;
        return configured == null ? "" : configured;
    }

    TransactionHistory history() { return history; }
    FavoritesStore favorites() { return favorites; }

    boolean isConfiguredMaterial(Material material) {
        return shop != null && shop.prices.containsKey(material);
    }

    String displayNameForSort(Material material) {
        return shop == null ? material.name() : ChatColor.stripColor(shop.pretty(material));
    }

    public void reloadPlugin() {
        reloadConfig();
        if (shop != null) shop.reload();
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this, VelocityBridgeListener.CHANNEL);
        if (imperialEconomy != null) {
            getServer().getServicesManager().unregister(Economy.class, imperialEconomy);
            imperialEconomy.shutdown();
            imperialEconomy = null;
        }
        if (history != null) history.shutdown();
        if (favorites != null) favorites.shutdown();
    }
}
