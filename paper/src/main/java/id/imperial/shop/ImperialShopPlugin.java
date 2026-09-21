package id.imperial.shop;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class ImperialShopPlugin extends JavaPlugin {
    private Economy economy;
    private ShopService shop;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        RegisteredServiceProvider<Economy> registration =
                getServer().getServicesManager().getRegistration(Economy.class);

        if (registration == null || registration.getProvider() == null) {
            getLogger().severe("Vault economy provider not found. ImperialShop disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        economy = registration.getProvider();
        shop = new ShopService(this, economy);

        if (getCommand("shop") != null) getCommand("shop").setExecutor(new ShopCommand(shop));
        if (getCommand("sell") != null) getCommand("sell").setExecutor(new SellCommand(shop));
        if (getCommand("sellall") != null) getCommand("sellall").setExecutor(new SellAllCommand(shop));
        if (getCommand("sellhand") != null) getCommand("sellhand").setExecutor(new SellHandCommand(shop));
        if (getCommand("imperialshop") != null) {
            getCommand("imperialshop").setExecutor(new AdminCommand(this, shop));
        }

        getServer().getPluginManager().registerEvents(new ShopListener(shop), this);
        getLogger().info("ImperialShop 2.0.0 enabled for Paper/Purpur 1.21.x.");
    }

    public void reloadPlugin() {
        reloadConfig();
        if (shop != null) shop.reload();
    }
}