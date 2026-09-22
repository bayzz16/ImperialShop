package id.imperial.shop;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.text.NumberFormat;
import java.util.*;

public final class ShopService {
    final ImperialShopPlugin plugin;
    final Economy economy;
    final NamespacedKey actionKey;
    final NamespacedKey itemKey;
    final Map<Material, Price> prices = new EnumMap<>(Material.class);
    final Map<String, Category> categories = new LinkedHashMap<>();
    private final Map<UUID, QuantitySession> quantities = new HashMap<>();
    private final Map<UUID, PendingTransaction> pendingTransactions = new HashMap<>();
    private final Map<UUID, Inventory> sellInputs = new HashMap<>();
    private final Map<UUID, Long> transactionCooldowns = new HashMap<>();

    enum GuiType {
        MAIN, CATEGORY, QUANTITY, CONFIRM, SEARCH, SELL_LIST, SELL_INPUT
    }

    static final class ShopHolder implements org.bukkit.inventory.InventoryHolder {
        private final GuiType type;
        private Inventory inventory;

        ShopHolder(GuiType type) {
            this.type = type;
        }

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        GuiType type() {
            return type;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private Inventory createGui(GuiType type, int size, String title) {
        ShopHolder holder = new ShopHolder(type);
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.bind(inventory);
        return inventory;
    }

    ShopService(ImperialShopPlugin plugin, Economy economy) {
        this.plugin = plugin;
        this.economy = economy;
        this.actionKey = new NamespacedKey(plugin, "action");
        this.itemKey = new NamespacedKey(plugin, "item");
        reload();
    }

    void reload() {
        prices.clear();
        categories.clear();

        var config = plugin.getShopConfig();
        var priceSection = config.getConfigurationSection("prices");
        if (priceSection != null) {
            for (String key : priceSection.getKeys(false)) {
                Material material = Material.matchMaterial(key);
                if (material == null) continue;
                double buy = priceSection.getDouble(key + ".buy", -1);
                double sell = priceSection.getDouble(key + ".sell", -1);
                int minBuy = Math.max(1, priceSection.getInt(key + ".min-buy", 1));
                int minSell = Math.max(1, priceSection.getInt(key + ".min-sell", 1));
                int maxBuy = Math.max(minBuy, priceSection.getInt(key + ".max-buy", quantityMax()));
                int maxSell = Math.max(minSell, priceSection.getInt(key + ".max-sell", quantityMax()));
                prices.put(material, new Price(buy, sell, minBuy, minSell, maxBuy, maxSell));
            }
        }

        var categorySection = config.getConfigurationSection("categories");
        if (categorySection != null) {
            for (String id : categorySection.getKeys(false)) {
                List<Material> items = new ArrayList<>();
                for (String raw : categorySection.getStringList(id + ".items")) {
                    Material material = Material.matchMaterial(raw);
                    if (material != null && prices.containsKey(material) && !items.contains(material)) {
                        items.add(material);
                    }
                }
                String materialName = categorySection.getString(id + ".material", "CHEST");
                Material icon = Material.matchMaterial(materialName);
                if (icon == null) icon = Material.CHEST;
                String name = color(categorySection.getString(id + ".name", id));
                String permission = categorySection.getString(id + ".permission", "");
                categories.put(id, new Category(id, name, icon, permission, items));
            }
        }
    }

    int pageSize() {
        return Math.max(9, Math.min(45, plugin.getConfig().getInt("settings.page-size", 45)));
    }

    int pageCapacity(int itemCount) {
        int limited = Math.max(1, Math.min(pageSize(), itemCount));
        int rows = Math.max(1, Math.min(5, (int) Math.ceil(limited / 9.0)));
        return rows * 9;
    }

    int compactGuiSize(int itemCount) {
        return pageCapacity(itemCount) + 9;
    }

    /** Centers shop entries horizontally on every content row. */
    int centeredSlot(int index, int itemCount) {
        if (itemCount <= 0) return 0;
        int row = index / 9;
        int column = index % 9;
        int itemsInRow = Math.min(9, itemCount - row * 9);
        int offset = Math.max(0, (9 - itemsInRow) / 2);
        return row * 9 + offset + column;
    }

    int quantityMax() {
        return Math.max(64, plugin.getConfig().getInt("settings.quantity-max", 2304));
    }

    int guiSize(String key) {
        if (key.equals("shop-size") || key.equals("sell-size")) return 54;
        return 27;
    }

    String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    String money(double value) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag(
                plugin.getConfig().getString("settings.locale", "id-ID")));
        format.setMaximumFractionDigits(2);
        format.setMinimumFractionDigits(0);
        return format.format(Math.max(0, value));
    }

    String pretty(Material material) {
        if (material == null) return "Unknown";
        String configured = plugin.getShopConfig().getString("display-names." + material.name());
        if (configured != null && !configured.isBlank()) return color(configured);

        StringBuilder result = new StringBuilder();
        for (String part : material.name().split("_")) {
            if (!result.isEmpty()) result.append(' ');
            result.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return result.toString();
    }

    boolean canUse(Player player) {
        return player.hasPermission("imperialshop.use");
    }

    boolean canAccessCategory(Player player, Category category) {
        if (player.hasPermission("imperialshop.shop.all")) return true;
        if (!category.permission().isBlank()) return player.hasPermission(category.permission());
        return player.hasPermission("imperialshop.shop." + category.id());
    }

    boolean canTrade(Player player, Category category, Material material) {
        if (!canAccessCategory(player, category)) return false;
        String itemPermission = plugin.getShopConfig().getString(
                "item-permissions." + category.id() + "." + material.name(), "");
        return itemPermission.isBlank() || player.hasPermission(itemPermission);
    }

    boolean canSellGui(Player player) {
        if (player.hasPermission("imperialshop.sellgui.all")) return true;
        return categories.values().stream()
                .anyMatch(category -> player.hasPermission("imperialshop.sellgui." + category.id()));
    }

    boolean canSellAllCategory(Player player, Category category) {
        return player.hasPermission("imperialshop.sellall.all")
                || player.hasPermission("imperialshop.sellall." + category.id());
    }

    boolean canSellAllItem(Player player, Category category, Material material) {
        if (!canSellAllCategory(player, category)) return false;
        String permission = plugin.getShopConfig().getString(
                "item-permissions." + category.id() + "." + material.name(), "");
        return permission.isBlank() || player.hasPermission(permission);
    }

    boolean canSellHand(Player player, Category category, Material material) {
        if (!(player.hasPermission("imperialshop.sellallhand.all")
                || player.hasPermission("imperialshop.sellallhand." + category.id()))) {
            return false;
        }
        String permission = plugin.getShopConfig().getString(
                "item-permissions." + category.id() + "." + material.name(), "");
        return permission.isBlank() || player.hasPermission(permission);
    }

    boolean canSellGuiItem(Player player, Category category, Material material) {
        boolean sectionPermission = player.hasPermission("imperialshop.sellgui.all")
                || player.hasPermission("imperialshop.sellgui." + category.id());
        if (!sectionPermission) return false;

        String permission = plugin.getShopConfig().getString(
                "item-permissions." + category.id() + "." + material.name(), "");
        return permission.isBlank() || player.hasPermission(permission);
    }

    Category categoryFor(Material material) {
        for (Category category : categories.values()) {
            if (category.items().contains(material)) return category;
        }
        return null;
    }

    double buyPrice(Player player, Material material) {
        Price price = prices.get(material);
        if (price == null || price.buy() < 0) return -1;
        return price.buy() * modifier(player, "buy");
    }

    double sellPrice(Player player, Material material) {
        Price price = prices.get(material);
        if (price == null || price.sell() < 0) return -1;
        return price.sell() * modifier(player, "sell");
    }

    private double modifier(Player player, String type) {
        double multiplier = 1.0;
        var section = plugin.getShopConfig().getConfigurationSection("price-modifiers");
        if (section == null) return multiplier;

        for (String id : section.getKeys(false)) {
            String permission = section.getString(id + ".permission", "");
            if (permission.isBlank() || player.hasPermission(permission)) {
                double value = section.getDouble(id + "." + type + "-multiplier", 1.0);
                if (Double.isFinite(value) && value >= 0) multiplier *= value;
            }
        }
        return multiplier;
    }

    ItemStack icon(Material material, String name, List<String> lore, String action, String data) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        if (data != null) {
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, data);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    ItemStack filler() {
        Material material = Material.matchMaterial(plugin.getConfig().getString("settings.filler-material", "GRAY_STAINED_GLASS_PANE"));
        if (material == null) material = Material.GRAY_STAINED_GLASS_PANE;
        return icon(material, " ", List.of(), "none", null);
    }

    void decorate(Inventory inventory) {
        if (!plugin.getConfig().getBoolean("settings.fill-empty-slots", true)) return;
        ItemStack filler = filler();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) inventory.setItem(slot, filler);
        }
    }

    void openShop(Player player) {
        openShop(player, 0);
    }

    void openSearchInput(Player player) {
        Inventory inventory = createGui(GuiType.SEARCH, 3,
                color("&b&lᴄᴀʀɪ ɪᴛᴇᴍ"));
        org.bukkit.inventory.AnvilInventory anvil = (org.bukkit.inventory.AnvilInventory) inventory;
        anvil.setRepairCost(0);

        ItemStack input = icon(Material.PAPER, color("&fKetik nama item..."),
                List.of(color("&7Masukkan nama/material item lalu ambil hasil.")),
                "search:input", null);
        inventory.setItem(0, input);

        player.openInventory(inventory);
        player.sendMessage(color("&b✦ &fSearch &8» &7Ketik nama item di kolom pencarian, lalu klik hasil."));
    }

    void openSearch(Player player, String query, int page) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Material> results = prices.keySet().stream()
                .filter(material -> {
                    Category category = categoryFor(material);
                    if (category == null || !canTrade(player, category, material)) return false;
                    return normalized.isBlank()
                            || material.name().toLowerCase(Locale.ROOT).contains(normalized)
                            || ChatColor.stripColor(pretty(material)).toLowerCase(Locale.ROOT).contains(normalized);
                })
                .sorted(Comparator.comparing(this::pretty))
                .toList();
        openMaterialResults(player, results, page, "&b&lSEARCH &8» &f" + (normalized.isBlank() ? "Semua Item" : normalized),
                "search:" + normalized);
    }

    void openFavorites(Player player, int page) {
        List<Material> results = plugin.favorites().get(player).stream()
                .filter(material -> {
                    Category category = categoryFor(material);
                    return category != null && canTrade(player, category, material);
                })
                .toList();
        openMaterialResults(player, results, page, "&d&lꜰᴀᴠᴏʀɪᴛ", "favorites");
    }

    private void openMaterialResults(Player player, List<Material> materials, int page, String title, String backData) {
        int capacity = pageCapacity(materials.size());
        int pages = Math.max(1, (int) Math.ceil(materials.size() / (double) capacity));
        page = Math.max(0, Math.min(page, pages - 1));

        Inventory inventory = createGui(GuiType.CATEGORY, compactGuiSize(materials.size()), color(title));

        int start = page * capacity;
        int end = Math.min(materials.size(), start + capacity);
        for (int i = start; i < end; i++) {
            Material material = materials.get(i);
            Category category = categoryFor(material);
            if (category == null || !canTrade(player, category, material)) continue;
            Price price = prices.get(material);

            List<String> lore = new ArrayList<>();
            if (price != null && price.buy() >= 0)
                lore.add(color("&8› &7Beli &8• &eRp " + money(buyPrice(player, material)) + " &8/1x"));
            if (price != null && price.sell() >= 0)
                lore.add(color("&8› &7Jual &8• &aRp " + money(sellPrice(player, material)) + " &8/1x"));
            lore.add("");
            lore.add(color("&8• &7Klik kiri &fBeli 1"));
            lore.add(color("&8• &7Klik kanan &fJual 1"));
            lore.add(color("&8• &7Shift kanan &fJual semua"));
            lore.add(color("&8• &7Tengah &fJumlah custom"));
            lore.add(color(plugin.favorites().isFavorite(player, material)
                    ? "&d★ &fFavorit" : "&8☆ &7Belum favorit"));

            inventory.setItem(centeredSlot(i - start, end - start), icon(material, color("&f&l" + pretty(material)), lore,
                    "resultitem", backData + "|" + category.id() + "|" + material.name()));
        }

        int bar = inventory.getSize() - 9;
        String encoded = Base64.getEncoder().encodeToString(
                backData.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        inventory.setItem(bar, nav(Material.ARROW, "&8‹ &eSebelumnya",
                "resultpage:" + encoded + ":" + (page - 1), page > 0));
        inventory.setItem(bar + 2, icon(Material.PAPER, color("&b&lᴄᴀʀɪ"),
                List.of(color("&8› &7/shop search <kata>")), "search", null));
        inventory.setItem(bar + 3, icon(Material.GOLD_INGOT, color("&6&lꜱᴀʟᴅᴏ"),
                List.of(color("&7Rp &e" + money(economy.getBalance(player)))), "none", null));
        inventory.setItem(bar + 4, icon(Material.BARRIER, color("&c&lᴛᴜᴛᴜᴘ"),
                List.of(), "close", null));
        inventory.setItem(bar + 6, icon(Material.ARROW, color("&8‹ &eꜱʜᴏᴘ"),
                List.of(), "shop", null));
        inventory.setItem(bar + 7, icon(Material.PAPER, color("&f" + (page + 1) + "&8/&f" + pages),
                List.of(color("&7" + materials.size() + " item")), "none", null));
        inventory.setItem(bar + 8, nav(Material.ARROW, "&aBerikutnya ›",
                "resultpage:" + encoded + ":" + (page + 1), page < pages - 1));

        decorate(inventory);
        // Re-apply controls after filler decoration.
        inventory.setItem(bar, nav(Material.ARROW, "&8‹ &eSebelumnya",
                "resultpage:" + encoded + ":" + (page - 1), page > 0));
        inventory.setItem(bar + 2, icon(Material.PAPER, color("&b&lᴄᴀʀɪ"),
                List.of(color("&8› &7/shop search <kata>")), "search", null));
        inventory.setItem(bar + 3, icon(Material.GOLD_INGOT, color("&6&lꜱᴀʟᴅᴏ"),
                List.of(color("&7Rp &e" + money(economy.getBalance(player)))), "none", null));
        inventory.setItem(bar + 4, icon(Material.BARRIER, color("&c&lᴛᴜᴛᴜᴘ"), List.of(), "close", null));
        inventory.setItem(bar + 6, icon(Material.ARROW, color("&8‹ &eꜱʜᴏᴘ"), List.of(), "shop", null));
        inventory.setItem(bar + 7, icon(Material.PAPER, color("&f" + (page + 1) + "&8/&f" + pages),
                List.of(color("&7" + materials.size() + " item")), "none", null));
        inventory.setItem(bar + 8, nav(Material.ARROW, "&aBerikutnya ›",
                "resultpage:" + encoded + ":" + (page + 1), page < pages - 1));
        player.openInventory(inventory);
    }


    void openShop(Player player, int page) {
        List<Category> accessible = categories.values().stream()
                .filter(category -> canAccessCategory(player, category))
                .toList();

        int capacity = pageCapacity(accessible.size());
        int pages = Math.max(1, (int) Math.ceil(accessible.size() / (double) capacity));
        page = Math.max(0, Math.min(page, pages - 1));

        Inventory inventory = createGui(GuiType.MAIN, compactGuiSize(accessible.size()),
                color(plugin.getConfig().getString("gui.main-title", "&b&lIMPERIAL SHOP")));

        int start = page * capacity;
        int end = Math.min(accessible.size(), start + capacity);
        for (int i = start; i < end; i++) {
            Category category = accessible.get(i);
            int slot = centeredSlot(i - start, end - start);
            inventory.setItem(slot, icon(category.icon(), category.name(),
                    List.of(
                            color("&8› &7Items &8• &f" + category.items().size()),
                            "",
                            color("&a&lKlik untuk membuka ›")
                    ), "category", category.id()));
        }

        int bar = inventory.getSize() - 9;
        inventory.setItem(bar, nav(Material.ARROW, "&8‹ &eSebelumnya",
                "mainpage:" + (page - 1), page > 0));
        inventory.setItem(bar + 1, icon(Material.PAPER, color("&b&lᴄᴀʀɪ"),
                List.of(color("&8› &7/shop search <kata>")), "search", null));
        inventory.setItem(bar + 2, icon(Material.GOLD_INGOT, color("&6&lꜱᴀʟᴅᴏ"),
                List.of(color("&7Rp &e" + money(economy.getBalance(player)))), "none", null));
        inventory.setItem(bar + 3, icon(Material.NETHER_STAR, color("&d&lꜰᴀᴠᴏʀɪᴛ"),
                List.of(color("&8› &7Item tersimpan")), "favorites", null));
        inventory.setItem(bar + 4, icon(Material.BARRIER, color("&c&lᴛᴜᴛᴜᴘ"), List.of(), "close", null));
        inventory.setItem(bar + 6, icon(Material.HOPPER, color("&a&lᴊᴜᴀʟ"),
                List.of(color("&8› &7Buka Sell GUI")), "sellgui", null));
        inventory.setItem(bar + 7, icon(Material.PAPER, color("&f" + (page + 1) + "&8/&f" + pages),
                List.of(color("&7" + accessible.size() + " kategori")), "none", null));
        inventory.setItem(bar + 8, nav(Material.ARROW, "&aBerikutnya ›",
                "mainpage:" + (page + 1), page < pages - 1));

        decorate(inventory);
        inventory.setItem(bar, nav(Material.ARROW, "&8‹ &eSebelumnya",
                "mainpage:" + (page - 1), page > 0));
        inventory.setItem(bar + 1, icon(Material.PAPER, color("&b&lᴄᴀʀɪ"),
                List.of(color("&8› &7/shop search <kata>")), "search", null));
        inventory.setItem(bar + 2, icon(Material.GOLD_INGOT, color("&6&lꜱᴀʟᴅᴏ"),
                List.of(color("&7Rp &e" + money(economy.getBalance(player)))), "none", null));
        inventory.setItem(bar + 3, icon(Material.NETHER_STAR, color("&d&lꜰᴀᴠᴏʀɪᴛ"),
                List.of(color("&8› &7Item tersimpan")), "favorites", null));
        inventory.setItem(bar + 4, icon(Material.BARRIER, color("&c&lᴛᴜᴛᴜᴘ"), List.of(), "close", null));
        inventory.setItem(bar + 6, icon(Material.HOPPER, color("&a&lᴊᴜᴀʟ"),
                List.of(color("&8› &7Buka Sell GUI")), "sellgui", null));
        inventory.setItem(bar + 7, icon(Material.PAPER, color("&f" + (page + 1) + "&8/&f" + pages),
                List.of(color("&7" + accessible.size() + " kategori")), "none", null));
        inventory.setItem(bar + 8, nav(Material.ARROW, "&aBerikutnya ›",
                "mainpage:" + (page + 1), page < pages - 1));
        player.openInventory(inventory);
    }


    void openCategory(Player player, String id) {
        openCategory(player, id, 0);
    }

    void openCategory(Player player, String id, int page) {
        Category category = categories.get(id);
        if (category == null || !canAccessCategory(player, category)) {
            message(player, "no-permission");
            return;
        }

        int capacity = pageCapacity(category.items().size());
        int pages = Math.max(1, (int) Math.ceil(category.items().size() / (double) capacity));
        page = Math.max(0, Math.min(page, pages - 1));

        Inventory inventory = createGui(GuiType.CATEGORY, compactGuiSize(category.items().size()),
                color(plugin.getConfig().getString("gui.category-title", "&b&lSHOP &8» &f%category%")
                        .replace("%category%", ChatColor.stripColor(category.name()))));

        int start = page * capacity;
        int end = Math.min(category.items().size(), start + capacity);
        for (int i = start; i < end; i++) {
            Material material = category.items().get(i);
            if (!canTrade(player, category, material)) continue;
            Price price = prices.get(material);

            List<String> lore = new ArrayList<>();
            if (price != null && price.buy() >= 0)
                lore.add(color("&eRp " + money(buyPrice(player, material)) + " &8• &7Beli"));
            if (price != null && price.sell() >= 0)
                lore.add(color("&aRp " + money(sellPrice(player, material)) + " &8• &7Jual"));
            lore.add("");
            lore.add(color("&8• &7Klik kiri &fBeli 1"));
            lore.add(color("&8• &7Klik kanan &fJual 1"));
            lore.add(color("&8• &7Shift kiri &fBeli 64"));
            lore.add(color("&8• &7Shift kanan &fJual semua"));
            lore.add(color("&8• &7Tengah &fJumlah custom"));

            inventory.setItem(centeredSlot(i - start, end - start), icon(material, color("&f&l" + pretty(material)), lore,
                    "item", category.id() + "|" + material.name()));
        }

        int bar = inventory.getSize() - 9;
        inventory.setItem(bar, icon(Material.ARROW, color("&8‹ &eꜱʜᴏᴘ"),
                List.of(), "back", null));
        inventory.setItem(bar + 2, icon(Material.HOPPER, color("&a&lᴊᴜᴀʟ"),
                List.of(color("&8› &7Buka Sell GUI")), "sellgui", null));
        inventory.setItem(bar + 3, icon(Material.PAPER, color("&f" + (page + 1) + "&8/&f" + pages),
                List.of(color("&7" + category.items().size() + " item")), "none", null));
        inventory.setItem(bar + 4, icon(Material.BARRIER, color("&c&lᴛᴜᴛᴜᴘ"), List.of(), "close", null));
        inventory.setItem(bar + 8, nav(Material.ARROW, "&aBerikutnya ›",
                "catpage:" + id + ":" + (page + 1), page < pages - 1));

        decorate(inventory);
        inventory.setItem(bar, icon(Material.ARROW, color("&8‹ &eꜱʜᴏᴘ"), List.of(), "back", null));
        inventory.setItem(bar + 2, icon(Material.HOPPER, color("&a&lᴊᴜᴀʟ"),
                List.of(color("&8› &7Buka Sell GUI")), "sellgui", null));
        inventory.setItem(bar + 3, icon(Material.PAPER, color("&f" + (page + 1) + "&8/&f" + pages),
                List.of(color("&7" + category.items().size() + " item")), "none", null));
        inventory.setItem(bar + 4, icon(Material.BARRIER, color("&c&lᴛᴜᴛᴜᴘ"), List.of(), "close", null));
        inventory.setItem(bar + 8, nav(Material.ARROW, "&aBerikutnya ›",
                "catpage:" + id + ":" + (page + 1), page < pages - 1));
        player.openInventory(inventory);
    }


    void openSellGui(Player player) {
        returnSellInput(player);

        Inventory inventory = createGui(GuiType.SELL_INPUT, 54,
                color(plugin.getConfig().getString("gui.sell-input-title", "&a&lIMPERIAL SELL GUI")));

        for (int slot = 0; slot < 45; slot++) {
            inventory.setItem(slot, null);
        }

        inventory.setItem(45, icon(Material.GOLD_INGOT, color("&a&lᴊᴜᴀʟ ꜱᴇᴍᴜᴀ"),
                List.of(color("&7Jual semua item valid yang ada di sini.")),
                "sellinput:all", null));
        inventory.setItem(47, icon(Material.CHEST, color("&e&lʟɪʜᴀᴛ ꜱᴇʟʟ ʟɪꜱᴛ"),
                List.of(color("&7Kembali ke daftar item yang bisa dijual.")),
                "sellgui:list", null));
        inventory.setItem(49, icon(Material.BARRIER, color("&cTUTUP"), List.of(),
                "close", null));
        inventory.setItem(51, icon(Material.ARROW, color("&eKEMBALI KE SHOP"), List.of(),
                "shop", null));
        inventory.setItem(53, icon(Material.PAPER, color("&f&lINFO"),
                List.of(
                        color("&7Taruh item yang ingin dijual"),
                        color("&7di 45 slot bagian atas."),
                        color("&7Item tidak terdaftar akan dikembalikan.")
                ), "none", null));

        sellInputs.put(player.getUniqueId(), inventory);
        player.openInventory(inventory);
    }

    void openSell(Player player) {
        List<Material> materials = new ArrayList<>();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir() || materials.contains(stack.getType())) continue;
            if (prices.containsKey(stack.getType())) {
                Category category = categoryFor(stack.getType());
                if (category != null && canSellGuiItem(player, category, stack.getType())) {
                    materials.add(stack.getType());
                }
            }
        }

        int capacity = pageCapacity(materials.size());
        Inventory inventory = createGui(GuiType.SELL_LIST, compactGuiSize(materials.size()),
                color(plugin.getConfig().getString("gui.sell-title", "&a&lꜱᴇʟʟ")));

        int slot = 0;
        for (Material material : materials) {
            if (slot >= capacity) break;
            int amount = count(player, material);
            double sell = sellPrice(player, material);
            inventory.setItem(slot++, icon(material, color("&f&l" + pretty(material)),
                    List.of(
                            color("&aRp " + money(sell) + " &8• &7/ item"),
                            color("&7Jumlah &8• &f" + amount),
                            "",
                            color("&8• &aKlik kiri &fJual semua"),
                            color("&8• &eKlik kanan &fJual 1"),
                            color("&8• &7Tengah &fJumlah custom")
                    ), "sell", material.name()));
        }

        int bar = inventory.getSize() - 9;
        inventory.setItem(bar, icon(Material.GOLD_INGOT, color("&a&lᴊᴜᴀʟ ꜱᴇᴍᴜᴀ"),
                List.of(color("&7Jual semua item terdaftar")), "sellall", null));
        inventory.setItem(bar + 2, icon(Material.CHEST, color("&e&lᴊᴜᴀʟ ᴛᴀɴɢᴀɴ"),
                List.of(color("&7Jual stack di tangan")), "sellhand", null));
        inventory.setItem(bar + 4, icon(Material.BARRIER, color("&c&lᴛᴜᴛᴜᴘ"), List.of(), "close", null));
        inventory.setItem(bar + 6, icon(Material.ARROW, color("&8‹ &eꜱʜᴏᴘ"), List.of(), "shop", null));

        decorate(inventory);
        inventory.setItem(bar, icon(Material.GOLD_INGOT, color("&a&lᴊᴜᴀʟ ꜱᴇᴍᴜᴀ"),
                List.of(color("&7Jual semua item terdaftar")), "sellall", null));
        inventory.setItem(bar + 2, icon(Material.CHEST, color("&e&lᴊᴜᴀʟ ᴛᴀɴɢᴀɴ"),
                List.of(color("&7Jual stack di tangan")), "sellhand", null));
        inventory.setItem(bar + 4, icon(Material.BARRIER, color("&c&lᴛᴜᴛᴜᴘ"), List.of(), "close", null));
        inventory.setItem(bar + 6, icon(Material.ARROW, color("&8‹ &eꜱʜᴏᴘ"), List.of(), "shop", null));
        player.openInventory(inventory);
    }


    void openQuantity(Player player, String categoryId, Material material) {
        Category category = categories.get(categoryId);
        if (category == null || !canTrade(player, category, material)) {
            message(player, "no-permission");
            return;
        }
        Price price = prices.get(material);
        if (price == null) {
            message(player, "no-permission");
            return;
        }
        int max = Math.min(quantityMax(), Math.max(price.maxBuy(), price.maxSell()));
        int initial = Math.min(max, Math.max(
                price.buy() >= 0 ? price.minBuy() : 1,
                price.sell() >= 0 ? price.minSell() : 1));
        quantities.put(player.getUniqueId(), new QuantitySession(categoryId, material, initial, max));
        renderQuantity(player);
    }

    private void renderQuantity(Player player) {
        QuantitySession session = quantities.get(player.getUniqueId());
        if (session == null) return;

        Material material = session.material();
        int amount = session.amount();
        Inventory inventory = createGui(GuiType.QUANTITY, 27,
                color(plugin.getConfig().getString("gui.quantity-title", "&b&lTRANSAKSI &8» &f%item%")
                        .replace("%item%", pretty(material))));

        Price price = prices.get(material);
        double buy = buyPrice(player, material);
        double sell = sellPrice(player, material);

        boolean canDecrease64 = amount > 64;
        boolean canDecrease16 = amount > 16;
        boolean canDecrease1 = amount > 1;
        boolean canIncrease1 = amount < session.max();
        boolean canIncrease16 = amount <= session.max() - 16;
        boolean canIncrease64 = amount <= session.max() - 64;

        // Universal amount controls: easy to use on Java and Bedrock/Geyser.
        inventory.setItem(10, nav(Material.REDSTONE_TORCH, "&c-64", "qty:-64", canDecrease64));
        inventory.setItem(11, nav(Material.REDSTONE, "&c-16", "qty:-16", canDecrease16));
        inventory.setItem(12, nav(Material.REDSTONE_BLOCK, "&c-1", "qty:-1", canDecrease1));
        inventory.setItem(13, icon(material, color("&f&l" + amount + "x"),
                List.of(
                        color("&7Beli: &eRp " + (buy >= 0 ? money(buy * amount) : "-")),
                        color("&7Jual: &aRp " + (sell >= 0 ? money(sell * amount) : "-")),
                        color("&7Jumlah: &f1 - " + session.max()),
                        "",
                        color("&8Klik preset di bawah untuk mengubah jumlah.")
                ), "none", null));
        inventory.setItem(14, nav(Material.GLOWSTONE_DUST, "&a+1", "qty:+1", canIncrease1));
        inventory.setItem(15, nav(Material.GLOWSTONE, "&a+16", "qty:+16", canIncrease16));
        inventory.setItem(16, nav(Material.SEA_LANTERN, "&a+64", "qty:+64", canIncrease64));

        // Bottom row: five amount presets + BUY/SELL/BACK/CLOSE.
        inventory.setItem(18, icon(Material.PAPER, color("&f&l1x"),
                List.of(color("&7Jumlah &f1")), "qty:set:1", null));
        inventory.setItem(19, icon(Material.PAPER, color("&f&l16x"),
                List.of(color("&7Jumlah &f16")), "qty:set:16", null));
        inventory.setItem(20, icon(Material.PAPER, color("&f&l32x"),
                List.of(color("&7Jumlah &f32")), "qty:set:32", null));
        inventory.setItem(21, icon(Material.PAPER, color("&f&l64x"),
                List.of(color("&7Jumlah &f64")), "qty:set:64", null));
        inventory.setItem(22, icon(Material.CHEST, color("&e&lMAX"),
                List.of(color("&7Jumlah &f" + session.max())), "qty:set:max", null));
        inventory.setItem(23, icon(Material.BARRIER, color("&c&lᴛᴜᴛᴜᴘ"), List.of(), "close", null));
        inventory.setItem(24, icon(Material.EMERALD, color("&a&lʙᴇʟɪ " + amount + "x"),
                List.of(color("&7Total: &eRp " + (buy >= 0 ? money(buy * amount) : "-"))),
                "buy:selected", null));
        inventory.setItem(25, icon(Material.GOLD_INGOT, color("&6&lᴊᴜᴀʟ " + amount + "x"),
                List.of(color("&7Total: &aRp " + (sell >= 0 ? money(sell * amount) : "-"))),
                "sell:selected", null));
        inventory.setItem(26, icon(Material.ARROW, color("&8‹ &eKembali"),
                List.of(color("&7Kembali ke daftar item")), "backcat", session.categoryId()));

        decorate(inventory);
        // Re-apply controls after decoration so filler cannot overwrite them.
        inventory.setItem(10, nav(Material.REDSTONE_TORCH, "&c-64", "qty:-64", canDecrease64));
        inventory.setItem(11, nav(Material.REDSTONE, "&c-16", "qty:-16", canDecrease16));
        inventory.setItem(12, nav(Material.REDSTONE_BLOCK, "&c-1", "qty:-1", canDecrease1));
        inventory.setItem(13, icon(material, color("&f&l" + amount + "x"),
                List.of(
                        color("&7Beli: &eRp " + (buy >= 0 ? money(buy * amount) : "-")),
                        color("&7Jual: &aRp " + (sell >= 0 ? money(sell * amount) : "-")),
                        color("&7Jumlah: &f1 - " + session.max())
                ), "none", null));
        inventory.setItem(14, nav(Material.GLOWSTONE_DUST, "&a+1", "qty:+1", canIncrease1));
        inventory.setItem(15, nav(Material.GLOWSTONE, "&a+16", "qty:+16", canIncrease16));
        inventory.setItem(16, nav(Material.SEA_LANTERN, "&a+64", "qty:+64", canIncrease64));
        inventory.setItem(18, icon(Material.EMERALD, color("&a&lʙᴇʟɪ " + amount + "x"),
                List.of(color("&7Total: &eRp " + (buy >= 0 ? money(buy * amount) : "-"))), "buy:selected", null));
        inventory.setItem(19, icon(Material.PAPER, color("&f&l1x"), List.of(color("&7Jumlah &f1")), "qty:set:1", null));
        inventory.setItem(20, icon(Material.PAPER, color("&f&l16x"), List.of(color("&7Jumlah &f16")), "qty:set:16", null));
        inventory.setItem(21, icon(Material.PAPER, color("&f&l32x"), List.of(color("&7Jumlah &f32")), "qty:set:32", null));
        inventory.setItem(22, icon(Material.PAPER, color("&f&l64x"), List.of(color("&7Jumlah &f64")), "qty:set:64", null));
        inventory.setItem(23, icon(Material.CHEST, color("&e&lMAX"), List.of(color("&7Jumlah &f" + session.max())), "qty:set:max", null));
        inventory.setItem(20, icon(Material.GOLD_INGOT, color("&6&lᴊᴜᴀʟ " + amount + "x"),
                List.of(color("&7Total: &aRp " + (sell >= 0 ? money(sell * amount) : "-"))), "sell:selected", null));
        inventory.setItem(24, icon(Material.ARROW, color("&8‹ &eKembali"),
                List.of(color("&7Kembali ke daftar item")), "backcat", session.categoryId()));
        inventory.setItem(26, icon(Material.BARRIER, color("&c&lᴛᴜᴛᴜᴘ"), List.of(), "close", null));
        player.openInventory(inventory);
    }

    void openPreview(Player player, String categoryId, Material material) {
        Category category = categories.get(categoryId);
        Price price = prices.get(material);
        if (category == null || price == null || !canTrade(player, category, material)) {
            message(player, "no-permission");
            return;
        }

        Inventory inventory = createGui(GuiType.CATEGORY, 27,
                color("&8✦ &b&lɪᴛᴇᴍ &f&lᴘʀᴇᴠɪᴇᴡ &8✦"));

        List<String> lore = new ArrayList<>();
        lore.add(color("&8› &7Kategori &8• &f" + ChatColor.stripColor(category.name())));
        if (price.buy() >= 0) lore.add(color("&8› &7Harga beli &8• &eRp " + money(buyPrice(player, material)) + " &7/1x"));
        if (price.sell() >= 0) lore.add(color("&8› &7Harga jual &8• &aRp " + money(sellPrice(player, material)) + " &7/1x"));
        lore.add("");
        lore.add(color("&7Klik &fBeli &7atau &fJual &7untuk memilih jumlah."));

        inventory.setItem(13, icon(material, color("&f&l" + pretty(material)), lore, "none", null));
        inventory.setItem(10, icon(Material.EMERALD, color("&a&lʙᴇʟɪ"),
                List.of(color("&7Pilih jumlah pembelian")), "preview:buy", categoryId + "|" + material.name()));
        inventory.setItem(16, icon(Material.GOLD_INGOT, color("&6&lᴊᴜᴀʟ"),
                List.of(color("&7Pilih jumlah penjualan")), "preview:sell", categoryId + "|" + material.name()));
        inventory.setItem(22, icon(Material.ARROW, color("&8‹ &eKembali"),
                List.of(), "backcat", categoryId));
        inventory.setItem(25, icon(Material.BARRIER, color("&c&lᴛᴜᴛᴜᴘ"), List.of(), "close", null));
        decorate(inventory);
        inventory.setItem(10, icon(Material.EMERALD, color("&a&lʙᴇʟɪ"),
                List.of(color("&7Pilih jumlah pembelian")), "preview:buy", categoryId + "|" + material.name()));
        inventory.setItem(16, icon(Material.GOLD_INGOT, color("&6&lᴊᴜᴀʟ"),
                List.of(color("&7Pilih jumlah penjualan")), "preview:sell", categoryId + "|" + material.name()));
        inventory.setItem(22, icon(Material.ARROW, color("&8‹ &eKembali"), List.of(), "backcat", categoryId));
        inventory.setItem(25, icon(Material.BARRIER, color("&c&lᴛᴜᴛᴜᴘ"), List.of(), "close", null));
        player.openInventory(inventory);
    }

    void changeQuantity(Player player, int delta) {
        QuantitySession current = quantities.get(player.getUniqueId());
        if (current == null) return;
        int next = Math.max(1, Math.min(current.max(), current.amount() + delta));
        quantities.put(player.getUniqueId(), new QuantitySession(
                current.categoryId(), current.material(), next, current.max()));
        renderQuantity(player);
    }

    void setQuantity(Player player, int amount) {
        QuantitySession current = quantities.get(player.getUniqueId());
        if (current == null) return;
        int next = Math.max(1, Math.min(current.max(), amount));
        quantities.put(player.getUniqueId(), new QuantitySession(
                current.categoryId(), current.material(), next, current.max()));
        renderQuantity(player);
    }

    int selectedQuantity(Player player) {
        QuantitySession session = quantities.get(player.getUniqueId());
        return session == null ? 1 : session.amount();
    }

    QuantitySession quantitySession(Player player) {
        return quantities.get(player.getUniqueId());
    }

    void clearQuantity(Player player) {
        quantities.remove(player.getUniqueId());
    }

    void openConfirmation(Player player, String action, String categoryId, Material material, int amount) {
        if (amount < 1) return;
        int threshold = Math.max(1, plugin.getConfig().getInt("settings.confirmation-threshold", 16));
        if (amount < threshold) {
            if ("buy".equals(action)) buy(player, material, amount);
            else sellMaterial(player, material, amount);
            return;
        }
        Category category = categories.get(categoryId);
        if (category == null || !canTrade(player, category, material)) return;
        Price price = prices.get(material);
        if (price == null) return;
        double unit = "buy".equals(action) ? buyPrice(player, material) : sellPrice(player, material);
        if (unit < 0 || !Double.isFinite(unit)) return;
        pendingTransactions.put(player.getUniqueId(), new PendingTransaction(action, categoryId, material, amount));
        renderConfirmation(player, unit * amount);
    }

    private void renderConfirmation(Player player, double total) {
        PendingTransaction pending = pendingTransactions.get(player.getUniqueId());
        if (pending == null) return;
        Inventory inventory = createGui(GuiType.CONFIRM, 27, color("&8✦ &e&lKONFIRMASI TRANSAKSI &8✦"));
        Material material = pending.material();
        boolean buy = "buy".equals(pending.action());
        inventory.setItem(11, icon(material, color("&f&l" + pretty(material)), List.of(
                color("&7Jumlah &8• &f" + pending.amount() + "x"),
                color("&7Total &8• &" + (buy ? "e" : "a") + "Rp " + money(total)),
                "",
                color(buy ? "&7Saldo akan dikurangi." : "&7Item akan diambil dari inventory.")
        ), "none", null));
        inventory.setItem(13, icon(buy ? Material.EMERALD : Material.GOLD_INGOT,
                color(buy ? "&a&lKONFIRMASI BELI" : "&6&lKONFIRMASI JUAL"),
                List.of(color("&7Klik untuk melanjutkan transaksi.")), "confirm", null));
        inventory.setItem(15, icon(Material.RED_CONCRETE, color("&c&lBATAL"),
                List.of(color("&7Kembali tanpa transaksi.")), "cancel", null));
        inventory.setItem(22, icon(Material.BARRIER, color("&8TUTUP"), List.of(), "cancel", null));
        decorate(inventory);
        inventory.setItem(11, icon(material, color("&f&l" + pretty(material)), List.of(
                color("&7Jumlah &8• &f" + pending.amount() + "x"),
                color("&7Total &8• &" + (buy ? "e" : "a") + "Rp " + money(total))
        ), "none", null));
        inventory.setItem(13, icon(buy ? Material.EMERALD : Material.GOLD_INGOT,
                color(buy ? "&a&lKONFIRMASI BELI" : "&6&lKONFIRMASI JUAL"),
                List.of(color("&7Klik untuk melanjutkan transaksi.")), "confirm", null));
        inventory.setItem(15, icon(Material.RED_CONCRETE, color("&c&lBATAL"), List.of(), "cancel", null));
        inventory.setItem(22, icon(Material.BARRIER, color("&8TUTUP"), List.of(), "cancel", null));
        player.openInventory(inventory);
    }

    void confirmPending(Player player) {
        PendingTransaction pending = pendingTransactions.remove(player.getUniqueId());
        if (pending == null) return;
        if ("buy".equals(pending.action())) buy(player, pending.material(), pending.amount());
        else sellMaterial(player, pending.material(), pending.amount());
    }

    void clearPending(Player player) {
        pendingTransactions.remove(player.getUniqueId());
    }

    private long transactionCooldownMillis() {
        return Math.max(0L, plugin.getConfig().getLong("settings.transaction-cooldown-ms", 250L));
    }

    private boolean tryTransaction(Player player) {
        long cooldown = transactionCooldownMillis();
        if (cooldown <= 0) return true;

        long now = System.currentTimeMillis();
        long last = transactionCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < cooldown) {
            sound(player, "fail");
            return false;
        }

        transactionCooldowns.put(player.getUniqueId(), now);
        return true;
    }

    boolean buy(Player player, Material material, int amount) {
        Category category = categoryFor(material);
        Price price = prices.get(material);
        if (category == null || price == null || amount < 1 || price.buy() < 0 || !canTrade(player, category, material)) {
            message(player, "no-permission");
            return false;
        }
        if (amount < price.minBuy()) {
            message(player, "below-min-buy", Map.of("%amount%", String.valueOf(price.minBuy())));
            sound(player, "fail");
            return false;
        }
        if (amount > price.maxBuy()) {
            message(player, "above-max-buy", Map.of("%amount%", String.valueOf(price.maxBuy())));
            sound(player, "fail");
            return false;
        }
        if (!tryTransaction(player)) return false;
        double unit = buyPrice(player, material);
        double total = unit * amount;
        if (!Double.isFinite(total) || total < 0) return false;

        if (!economy.has(player, total)) {
            message(player, "not-enough-money", Map.of("%price%", "Rp " + money(total)));
            sound(player, "fail");
            return false;
        }
        if (space(player, material) < amount) {
            message(player, "inventory-full");
            sound(player, "fail");
            return false;
        }

        EconomyResponse response = economy.withdrawPlayer(player, total);
        if (!response.transactionSuccess()) return false;

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(material, amount));
        if (!leftover.isEmpty()) {
            economy.depositPlayer(player, total);
            message(player, "inventory-full");
            return false;
        }

        message(player, "bought", Map.of(
                "%amount%", String.valueOf(amount),
                "%item%", pretty(material),
                "%price%", "Rp " + money(total)));
        plugin.history().record(player, "BUY", material, amount, total);
        sound(player, "buy");
        return true;
    }

    private double sellValidated(Player player, Material material, int requested,
                                  boolean guiPermission, boolean ignoreMaxSell) {
        Category category = categoryFor(material);
        Price price = prices.get(material);

        if (category == null || price == null || price.sell() < 0 || requested < 1) return 0;
        boolean allowed = guiPermission
                ? canSellGuiItem(player, category, material)
                : canTrade(player, category, material);
        if (!allowed) return 0;

        int amount = requested;
        int available = count(player, material);
        if (!ignoreMaxSell) {
            if (requested == Integer.MAX_VALUE) {
                amount = Math.min(price.maxSell(), available);
            } else {
                if (amount < price.minSell()) {
                    message(player, "below-min-sell", Map.of("%amount%", String.valueOf(price.minSell())));
                    sound(player, "fail");
                    return 0;
                }
                if (amount > price.maxSell()) {
                    message(player, "above-max-sell", Map.of("%amount%", String.valueOf(price.maxSell())));
                    sound(player, "fail");
                    return 0;
                }
                amount = Math.min(amount, available);
            }
        } else {
            amount = available;
        }
        if (amount < price.minSell() && !ignoreMaxSell) {
            message(player, "below-min-sell", Map.of("%amount%", String.valueOf(price.minSell())));
            sound(player, "fail");
            return 0;
        }
        if (amount < 1) return 0;
        if (!tryTransaction(player)) return 0;

        double total = sellPrice(player, material) * amount;
        if (!Double.isFinite(total) || total < 0) return 0;
        if (!removeMaterial(player, material, amount)) return 0;

        EconomyResponse response = economy.depositPlayer(player, total);
        if (!response.transactionSuccess()) {
            addMaterialBack(player, material, amount);
            return 0;
        }

        message(player, "sold", Map.of(
                "%amount%", String.valueOf(amount),
                "%item%", pretty(material),
                "%price%", "Rp " + money(total)));
        plugin.history().record(player, "SELL", material, amount, total);
        sound(player, "sell");
        return total;
    }

    double sellGuiMaterial(Player player, Material material, int requested) {
        return sellValidated(player, material, requested, true, false);
    }

    double sellGuiAllMaterial(Player player, Material material) {
        return sellValidated(player, material, Integer.MAX_VALUE, true, true);
    }

    double sellMaterial(Player player, Material material, int requested) {
        return sellValidated(player, material, requested, false, false);
    }

    double sellHand(Player player) {
        ItemStack stack = player.getInventory().getItemInMainHand();
        if (stack == null || stack.getType().isAir()) return 0;

        Material material = stack.getType();
        Category category = categoryFor(material);
        if (category == null || !canSellHand(player, category, material)) return 0;

        ItemStack backup = stack.clone();
        int amount = backup.getAmount();
        double total = sellPrice(player, material) * amount;
        if (amount < 1 || !Double.isFinite(total) || total < 0) return 0;
        if (!tryTransaction(player)) return 0;

        player.getInventory().setItemInMainHand(null);

        EconomyResponse response = economy.depositPlayer(player, total);
        if (!response.transactionSuccess()) {
            player.getInventory().setItemInMainHand(backup);
            return 0;
        }

        message(player, "sold", Map.of(
                "%amount%", String.valueOf(amount),
                "%item%", pretty(material),
                "%price%", "Rp " + money(total)));
        plugin.history().record(player, "SELL", material, amount, total);
        sound(player, "sell");
        return total;
    }

    double sellAllMaterial(Player player, Material material) {
        Category category = categoryFor(material);
        Price price = prices.get(material);
        if (category == null || price == null || price.sell() < 0
                || !canSellAllItem(player, category, material)) return 0;

        int amount = count(player, material);
        if (amount < 1) return 0;
        if (!tryTransaction(player)) return 0;

        double total = sellPrice(player, material) * amount;
        if (!Double.isFinite(total) || total < 0) return 0;
        if (!removeMaterial(player, material, amount)) return 0;

        EconomyResponse response = economy.depositPlayer(player, total);
        if (!response.transactionSuccess()) {
            addMaterialBack(player, material, amount);
            return 0;
        }

        message(player, "sold", Map.of(
                "%amount%", String.valueOf(amount),
                "%item%", pretty(material),
                "%price%", "Rp " + money(total)));
        plugin.history().record(player, "SELL", material, amount, total);
        sound(player, "sell");
        return total;
    }

    double sellInput(Player player) {
        Inventory input = sellInputs.get(player.getUniqueId());
        if (input == null || !canSellGui(player)) return 0;

        double total = 0;
        List<SlotBackup> sold = new ArrayList<>();

        for (int slot = 0; slot < 45; slot++) {
            ItemStack stack = input.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;

            Material material = stack.getType();
            Category category = categoryFor(material);
            Price price = prices.get(material);
            if (category == null || price == null || price.sell() < 0
                    || !canSellGuiItem(player, category, material)) continue;

            int amount = stack.getAmount();
            double value = sellPrice(player, material) * amount;
            if (amount < 1 || !Double.isFinite(value) || value < 0) continue;

            sold.add(new SlotBackup(slot, stack.clone()));
            total += value;
        }

        if (sold.isEmpty() || total <= 0) return 0;
        if (!tryTransaction(player)) return 0;

        for (SlotBackup backup : sold) {
            input.setItem(backup.slot(), null);
        }

        EconomyResponse response = economy.depositPlayer(player, total);
        if (!response.transactionSuccess()) {
            for (SlotBackup backup : sold) {
                input.setItem(backup.slot(), backup.stack());
            }
            return 0;
        }

        message(player, "sold-gui", Map.of("%price%", "Rp " + money(total)));
        for (SlotBackup backup : sold) {
            ItemStack stack = backup.stack();
            plugin.history().record(player, "SELL", stack.getType(), stack.getAmount(),
                    sellPrice(player, stack.getType()) * stack.getAmount());
        }
        sound(player, "sell");
        return total;
    }

    void returnSellInput(Player player) {
        Inventory input = sellInputs.remove(player.getUniqueId());
        if (input == null) return;

        for (int slot = 0; slot < 45; slot++) {
            ItemStack stack = input.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;

            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            if (!leftover.isEmpty()) {
                for (ItemStack drop : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            }
            input.setItem(slot, null);
        }
    }

    double sellContents(Player player) {
        double total = 0;
        List<SlotBackup> backups = new ArrayList<>();

        for (int slot = 0; slot < player.getInventory().getStorageContents().length; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;

            Material material = stack.getType();
            Category category = categoryFor(material);
            Price price = prices.get(material);
            if (category == null || price == null || price.sell() < 0
                    || !canSellAllItem(player, category, material)) continue;

            int amount = stack.getAmount();
            double value = sellPrice(player, material) * amount;
            if (amount < 1 || !Double.isFinite(value) || value < 0) continue;

            backups.add(new SlotBackup(slot, stack.clone()));
            total += value;
            player.getInventory().setItem(slot, null);
        }

        if (backups.isEmpty() || total <= 0) {
            for (SlotBackup backup : backups) {
                player.getInventory().setItem(backup.slot(), backup.stack());
            }
            return 0;
        }
        if (!tryTransaction(player)) {
            for (SlotBackup backup : backups) {
                player.getInventory().setItem(backup.slot(), backup.stack());
            }
            return 0;
        }

        EconomyResponse response = economy.depositPlayer(player, total);
        if (!response.transactionSuccess()) {
            for (SlotBackup backup : backups) {
                player.getInventory().setItem(backup.slot(), backup.stack());
            }
            return 0;
        }

        message(player, "sold", Map.of(
                "%amount%", "inventory",
                "%item%", "items",
                "%price%", "Rp " + money(total)));
        for (SlotBackup backup : backups) {
            ItemStack stack = backup.stack();
            plugin.history().record(player, "SELL", stack.getType(), stack.getAmount(),
                    sellPrice(player, stack.getType()) * stack.getAmount());
        }
        sound(player, "sell");
        return total;
    }

    int count(Player player, Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material) total += stack.getAmount();
        }
        return total;
    }
    int space(Player player, Material material) {
        int total = 0;
        ItemStack template = new ItemStack(material);
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) total += material.getMaxStackSize();
            else if (stack.isSimilar(template)) total += material.getMaxStackSize() - stack.getAmount();
        }
        return total;
    }

    private boolean removeMaterial(Player player, Material material, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < player.getInventory().getStorageContents().length && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack == null || stack.getType() != material) continue;
            int take = Math.min(remaining, stack.getAmount());
            if (take == stack.getAmount()) player.getInventory().setItem(slot, null);
            else stack.setAmount(stack.getAmount() - take);
            remaining -= take;
        }
        return remaining == 0;
    }

    private void addMaterialBack(Player player, Material material, int amount) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(material, amount));
        if (!leftover.isEmpty()) {
            for (ItemStack stack : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), stack);
            }
        }
    }

    void cleanup(Player player) {
        UUID uuid = player.getUniqueId();
        quantities.remove(uuid);
        pendingTransactions.remove(uuid);
        transactionCooldowns.remove(uuid);
        returnSellInput(player);
    }

    private ItemStack nav(Material material, String name, String action, boolean enabled) {
        return icon(material, color(enabled ? name : "&8" + ChatColor.stripColor(color(name))),
                enabled ? List.of() : List.of(color("&7Tidak tersedia")), enabled ? action : "none", null);
    }

    private void message(Player player, String key) {
        message(player, key, Collections.emptyMap());
    }

    private void message(Player player, String key, Map<String, String> replacements) {
        String value = plugin.getConfig().getString("messages." + key, "");
        for (var entry : replacements.entrySet()) value = value.replace(entry.getKey(), entry.getValue());
        if (!value.isBlank()) player.sendMessage(color(value));
    }

    private void sound(Player player, String type) {
        if (!plugin.getConfig().getBoolean("settings.sounds", true)) return;
        String configured = plugin.getConfig().getString("sounds." + type, "");
        if (configured.isBlank()) return;
        try {
            player.playSound(player.getLocation(), Sound.valueOf(configured.toUpperCase(Locale.ROOT)), 1f, 1f);
        } catch (IllegalArgumentException ignored) {
        }
    }

    record Price(double buy, double sell, int minBuy, int minSell, int maxBuy, int maxSell) {}
    record Category(String id, String name, Material icon, String permission, List<Material> items) {}
    record QuantitySession(String categoryId, Material material, int amount, int max) {}

    record PendingTransaction(String action, String categoryId, Material material, int amount) {}
    record SlotBackup(int slot, ItemStack stack) {}
}