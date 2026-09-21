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

        var config = plugin.getConfig();
        var priceSection = config.getConfigurationSection("prices");
        if (priceSection != null) {
            for (String key : priceSection.getKeys(false)) {
                Material material = Material.matchMaterial(key);
                if (material == null) continue;
                double buy = priceSection.getDouble(key + ".buy", -1);
                double sell = priceSection.getDouble(key + ".sell", -1);
                int maxBuy = Math.max(1, priceSection.getInt(key + ".max-buy", quantityMax()));
                int maxSell = Math.max(1, priceSection.getInt(key + ".max-sell", quantityMax()));
                prices.put(material, new Price(buy, sell, maxBuy, maxSell));
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

    int quantityMax() {
        return Math.max(64, plugin.getConfig().getInt("settings.quantity-max", 2304));
    }

    int guiSize(String key) {
        int size = plugin.getConfig().getInt("settings." + key, 54);
        if (size != 27 && size != 36 && size != 45 && size != 54) size = 54;
        return size;
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
        String configured = plugin.getConfig().getString("display-names." + material.name());
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
        if (!category.permission().isBlank()) return player.hasPermission(category.permission());
        return player.hasPermission("imperialshop.shop.all")
                || player.hasPermission("imperialshop.shop." + category.id());
    }

    boolean canTrade(Player player, Category category, Material material) {
        if (!canAccessCategory(player, category)) return false;
        String itemPermission = plugin.getConfig().getString(
                "item-permissions." + category.id() + "." + material.name(), "");
        return itemPermission.isBlank() || player.hasPermission(itemPermission);
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
        var section = plugin.getConfig().getConfigurationSection("price-modifiers");
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

    void openShop(Player player, int page) {
        List<Category> accessible = categories.values().stream()
                .filter(category -> canAccessCategory(player, category))
                .toList();

        int pages = Math.max(1, (int) Math.ceil(accessible.size() / (double) pageSize()));
        page = Math.max(0, Math.min(page, pages - 1));

        Inventory inventory = Bukkit.createInventory(null, guiSize("shop-size"),
                color(plugin.getConfig().getString("gui.main-title", "&b&lIMPERIAL SHOP")));

        int start = page * pageSize();
        int end = Math.min(accessible.size(), start + pageSize());
        for (int i = start; i < end; i++) {
            Category category = accessible.get(i);
            int slot = i - start;
            inventory.setItem(slot, icon(category.icon(), category.name(),
                    List.of(
                            color("&7Items: &f" + category.items().size()),
                            "",
                            color("&aKlik untuk membuka")
                    ), "category", category.id()));
        }

        inventory.setItem(45, nav(Material.ARROW, "&eHalaman Sebelumnya", "page:-1", page > 0));
        inventory.setItem(47, icon(Material.GOLD_INGOT,
                color("&6&lSaldo"),
                List.of(color("&7Saldo: &eRp " + money(economy.getBalance(player)))),
                "none", null));
        inventory.setItem(49, icon(Material.BARRIER, color("&cTutup"), List.of(), "close", null));
        inventory.setItem(51, icon(Material.HOPPER, color("&a&lJual Item"),
                List.of(color("&7Buka Sell GUI")), "sellgui", null));
        inventory.setItem(53, nav(Material.ARROW, "&aHalaman Berikutnya", "page:+1", page < pages - 1));
        decorate(inventory);
        // Re-apply navigation after filler.
        inventory.setItem(45, nav(Material.ARROW, "&eHalaman Sebelumnya", "page:-1", page > 0));
        inventory.setItem(47, icon(Material.GOLD_INGOT, color("&6&lSaldo"),
                List.of(color("&7Saldo: &eRp " + money(economy.getBalance(player)))),
                "none", null));
        inventory.setItem(49, icon(Material.BARRIER, color("&cTutup"), List.of(), "close", null));
        inventory.setItem(51, icon(Material.HOPPER, color("&a&lJual Item"),
                List.of(color("&7Buka Sell GUI")), "sellgui", null));
        inventory.setItem(53, nav(Material.ARROW, "&aHalaman Berikutnya", "page:+1", page < pages - 1));
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

        int pages = Math.max(1, (int) Math.ceil(category.items().size() / (double) pageSize()));
        page = Math.max(0, Math.min(page, pages - 1));

        Inventory inventory = Bukkit.createInventory(null, guiSize("shop-size"),
                color(plugin.getConfig().getString("gui.category-title", "&b&lSHOP &8» &f%category%")
                        .replace("%category%", ChatColor.stripColor(category.name()))));

        int start = page * pageSize();
        int end = Math.min(category.items().size(), start + pageSize());
        for (int i = start; i < end; i++) {
            Material material = category.items().get(i);
            if (!canTrade(player, category, material)) continue;
            Price price = prices.get(material);

            List<String> lore = new ArrayList<>();
            if (price != null && price.buy() >= 0) {
                lore.add(color("&7Beli: &eRp " + money(buyPrice(player, material)) + " &8(1x)"));
                lore.add(color("&7Shift+Click: &e64x"));
            } else {
                lore.add(color("&cTidak dapat dibeli"));
            }
            if (price != null && price.sell() >= 0) {
                lore.add(color("&7Jual: &aRp " + money(sellPrice(player, material)) + " &8(1x)"));
                lore.add(color("&7Shift+Klik kanan: &aJual semua"));
            } else {
                lore.add(color("&cTidak dapat dijual"));
            }
            lore.add("");
            lore.add(color("&fKlik kiri &7→ beli 1"));
            lore.add(color("&fKlik kanan &7→ jual 1"));
            lore.add(color("&fShift kiri &7→ beli 64"));
            lore.add(color("&fShift kanan &7→ jual semua"));
            lore.add(color("&fKlik tengah &7→ jumlah custom"));

            inventory.setItem(i - start, icon(material, color("&f&l" + pretty(material)), lore,
                    "item", category.id() + "|" + material.name()));
        }

        inventory.setItem(45, icon(Material.ARROW, color("&eKembali"), List.of(), "back", null));
        inventory.setItem(47, icon(Material.HOPPER, color("&aJual GUI"), List.of(), "sellgui", null));
        inventory.setItem(49, icon(Material.BARRIER, color("&cTutup"), List.of(), "close", null));
        inventory.setItem(51, icon(Material.PAPER, color("&fHalaman " + (page + 1) + "/" + pages),
                List.of(color("&7Kategori: &f" + category.name())), "none", null));
        inventory.setItem(53, nav(Material.ARROW, "&aBerikutnya", "catpage:" + id + ":" + (page + 1), page < pages - 1));
        inventory.setItem(43, nav(Material.ARROW, "&eSebelumnya", "catpage:" + id + ":" + (page - 1), page > 0));
        decorate(inventory);
        inventory.setItem(45, icon(Material.ARROW, color("&eKembali"), List.of(), "back", null));
        inventory.setItem(47, icon(Material.HOPPER, color("&aJual GUI"), List.of(), "sellgui", null));
        inventory.setItem(49, icon(Material.BARRIER, color("&cTutup"), List.of(), "close", null));
        inventory.setItem(51, icon(Material.PAPER, color("&fHalaman " + (page + 1) + "/" + pages),
                List.of(color("&7Kategori: &f" + category.name())), "none", null));
        inventory.setItem(43, nav(Material.ARROW, "&eSebelumnya", "catpage:" + id + ":" + (page - 1), page > 0));
        inventory.setItem(53, nav(Material.ARROW, "&aBerikutnya", "catpage:" + id + ":" + (page + 1), page < pages - 1));
        player.openInventory(inventory);
    }

    void openSell(Player player) {
        Inventory inventory = Bukkit.createInventory(null, guiSize("sell-size"),
                color(plugin.getConfig().getString("gui.sell-title", "&a&lSELL GUI")));

        List<Material> materials = new ArrayList<>();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir() || !materials.contains(stack.getType())) {
                if (stack != null && !stack.getType().isAir() && prices.containsKey(stack.getType())) {
                    Category category = categoryFor(stack.getType());
                    if (category != null && canTrade(player, category, stack.getType())) {
                        materials.add(stack.getType());
                    }
                }
            }
        }

        int slot = 0;
        for (Material material : materials) {
            if (slot >= pageSize()) break;
            int amount = count(player, material);
            double sell = sellPrice(player, material);
            inventory.setItem(slot++, icon(material, color("&f&l" + pretty(material)),
                    List.of(
                            color("&7Harga: &aRp " + money(sell) + " &8/ item"),
                            color("&7Jumlah: &f" + amount),
                            "",
                            color("&aKlik kiri &7→ jual semua"),
                            color("&eKlik kanan &7→ jual 1"),
                            color("&fKlik tengah &7→ jumlah custom")
                    ), "sell", material.name()));
        }

        inventory.setItem(45, icon(Material.GOLD_INGOT, color("&a&lJual Semua"),
                List.of(color("&7Jual semua item yang terdaftar.")), "sellall", null));
        inventory.setItem(47, icon(Material.CHEST, color("&e&lJual Tangan"),
                List.of(color("&7Jual seluruh stack di tangan.")), "sellhand", null));
        inventory.setItem(49, icon(Material.BARRIER, color("&cTutup"), List.of(), "close", null));
        inventory.setItem(51, icon(Material.ARROW, color("&eKembali ke Shop"), List.of(), "shop", null));
        decorate(inventory);
        inventory.setItem(45, icon(Material.GOLD_INGOT, color("&a&lJual Semua"),
                List.of(color("&7Jual semua item yang terdaftar.")), "sellall", null));
        inventory.setItem(47, icon(Material.CHEST, color("&e&lJual Tangan"),
                List.of(color("&7Jual seluruh stack di tangan.")), "sellhand", null));
        inventory.setItem(49, icon(Material.BARRIER, color("&cTutup"), List.of(), "close", null));
        inventory.setItem(51, icon(Material.ARROW, color("&eKembali ke Shop"), List.of(), "shop", null));
        player.openInventory(inventory);
    }

    void openQuantity(Player player, String categoryId, Material material) {
        Category category = categories.get(categoryId);
        if (category == null || !canTrade(player, category, material)) {
            message(player, "no-permission");
            return;
        }
        int max = Math.min(quantityMax(), Math.max(
                prices.get(material).maxBuy(), prices.get(material).maxSell()));
        quantities.put(player.getUniqueId(), new QuantitySession(categoryId, material, 1, max));
        renderQuantity(player);
    }

    private void renderQuantity(Player player) {
        QuantitySession session = quantities.get(player.getUniqueId());
        if (session == null) return;

        Material material = session.material();
        int amount = session.amount();
        Inventory inventory = Bukkit.createInventory(null, 27,
                color(plugin.getConfig().getString("gui.quantity-title", "&b&lTRANSAKSI &8» &f%item%")
                        .replace("%item%", pretty(material))));

        Price price = prices.get(material);
        double buy = buyPrice(player, material);
        double sell = sellPrice(player, material);

        inventory.setItem(10, icon(Material.REDSTONE_TORCH, color("&c-16"), List.of(), "qty:-16", null));
        inventory.setItem(11, icon(Material.REDSTONE, color("&c-1"), List.of(), "qty:-1", null));
        inventory.setItem(13, icon(material, color("&f&l" + amount + "x"),
                List.of(
                        color("&7Beli: &eRp " + (buy >= 0 ? money(buy * amount) : "-")),
                        color("&7Jual: &aRp " + (sell >= 0 ? money(sell * amount) : "-")),
                        color("&7Maksimum: &f" + session.max())
                ), "none", null));
        inventory.setItem(15, icon(Material.GLOWSTONE_DUST, color("&a+1"), List.of(), "qty:+1", null));
        inventory.setItem(16, icon(Material.GLOWSTONE, color("&a+16"), List.of(), "qty:+16", null));
        inventory.setItem(20, icon(Material.EMERALD, color("&a&lBeli " + amount + "x"),
                List.of(color("&7Total: &eRp " + (buy >= 0 ? money(buy * amount) : "-"))),
                "buy:selected", null));
        inventory.setItem(24, icon(Material.GOLD_INGOT, color("&6&lJual " + amount + "x"),
                List.of(color("&7Total: &aRp " + (sell >= 0 ? money(sell * amount) : "-"))),
                "sell:selected", null));
        inventory.setItem(22, icon(Material.ARROW, color("&eKembali"), List.of(), "backcat", session.categoryId()));
        inventory.setItem(26, icon(Material.BARRIER, color("&cTutup"), List.of(), "close", null));
        decorate(inventory);
        inventory.setItem(10, icon(Material.REDSTONE_TORCH, color("&c-16"), List.of(), "qty:-16", null));
        inventory.setItem(11, icon(Material.REDSTONE, color("&c-1"), List.of(), "qty:-1", null));
        inventory.setItem(13, icon(material, color("&f&l" + amount + "x"),
                List.of(color("&7Beli: &eRp " + (buy >= 0 ? money(buy * amount) : "-")),
                        color("&7Jual: &aRp " + (sell >= 0 ? money(sell * amount) : "-"))),
                "none", null));
        inventory.setItem(15, icon(Material.GLOWSTONE_DUST, color("&a+1"), List.of(), "qty:+1", null));
        inventory.setItem(16, icon(Material.GLOWSTONE, color("&a+16"), List.of(), "qty:+16", null));
        inventory.setItem(20, icon(Material.EMERALD, color("&a&lBeli " + amount + "x"),
                List.of(color("&7Total: &eRp " + (buy >= 0 ? money(buy * amount) : "-"))),
                "buy:selected", null));
        inventory.setItem(24, icon(Material.GOLD_INGOT, color("&6&lJual " + amount + "x"),
                List.of(color("&7Total: &aRp " + (sell >= 0 ? money(sell * amount) : "-"))),
                "sell:selected", null));
        inventory.setItem(22, icon(Material.ARROW, color("&eKembali"), List.of(), "backcat", session.categoryId()));
        inventory.setItem(26, icon(Material.BARRIER, color("&cTutup"), List.of(), "close", null));
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

    int selectedQuantity(Player player) {
        QuantitySession session = quantities.get(player.getUniqueId());
        return session == null ? 1 : session.amount();
    }

    void clearQuantity(Player player) {
        quantities.remove(player.getUniqueId());
    }

    boolean buy(Player player, Material material, int amount) {
        Category category = categoryFor(material);
        Price price = prices.get(material);
        if (category == null || price == null || amount < 1 || price.buy() < 0 || !canTrade(player, category, material)) {
            message(player, "no-permission");
            return false;
        }
        amount = Math.min(amount, price.maxBuy());
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
        sound(player, "buy");
        return true;
    }

    double sellMaterial(Player player, Material material, int requested) {
        Category category = categoryFor(material);
        Price price = prices.get(material);
        if (category == null || price == null || price.sell() < 0 || requested < 1
                || !canTrade(player, category, material)) return 0;

        int amount = Math.min(requested, price.maxSell());
        int available = count(player, material);
        amount = Math.min(amount, available);
        if (amount < 1) return 0;

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
        sound(player, "sell");
        return total;
    }

    double sellHand(Player player) {
        ItemStack stack = player.getInventory().getItemInMainHand();
        if (stack == null || stack.getType().isAir()) return 0;
        return sellMaterial(player, stack.getType(), stack.getAmount());
    }

    double sellContents(Player player) {
        double total = 0;
        List<SlotBackup> backups = new ArrayList<>();

        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;

            Category category = categoryFor(stack.getType());
            Price price = prices.get(stack.getType());
            if (category == null || price == null || price.sell() < 0 || !canTrade(player, category, stack.getType())) continue;

            int amount = Math.min(stack.getAmount(), price.maxSell());
            if (amount < 1) continue;

            total += sellPrice(player, stack.getType()) * amount;
            if (amount == stack.getAmount()) {
                backups.add(new SlotBackup(slot, stack.clone()));
                player.getInventory().setItem(slot, null);
            } else {
                backups.add(new SlotBackup(slot, stack.clone()));
                stack.setAmount(stack.getAmount() - amount);
            }
        }

        if (backups.isEmpty() || total <= 0) return 0;

        EconomyResponse response = economy.depositPlayer(player, total);
        if (!response.transactionSuccess()) {
            for (SlotBackup backup : backups) player.getInventory().setItem(backup.slot(), backup.stack());
            return 0;
        }

        message(player, "sold", Map.of(
                "%amount%", "inventory",
                "%item%", "items",
                "%price%", "Rp " + money(total)));
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
        for (int slot = 0; slot < player.getInventory().getSize() && remaining > 0; slot++) {
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

    record Price(double buy, double sell, int maxBuy, int maxSell) {}
    record Category(String id, String name, Material icon, String permission, List<Material> items) {}
    record QuantitySession(String categoryId, Material material, int amount, int max) {}
    record SlotBackup(int slot, ItemStack stack) {}
}