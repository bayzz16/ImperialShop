package id.imperial.shop;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.text.NumberFormat;
import java.util.*;

public final class ShopService {
    final ImperialShopPlugin plugin;
    final Economy economy;
    final NamespacedKey actionKey, itemKey, qtyKey;
    final Map<Material, Price> prices = new EnumMap<>(Material.class);
    final Map<String, List<Material>> cats = new LinkedHashMap<>();
    private final Map<UUID, Integer> quantities = new HashMap<>();

    ShopService(ImperialShopPlugin p, Economy e) {
        plugin = p;
        economy = e;
        actionKey = new NamespacedKey(p, "action");
        itemKey = new NamespacedKey(p, "item");
        qtyKey = new NamespacedKey(p, "quantity");
        reload();
    }

    void reload() {
        prices.clear();
        cats.clear();
        var pc = plugin.getConfig();

        var sec = pc.getConfigurationSection("prices");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m != null) {
                    prices.put(m, new Price(sec.getDouble(key + ".buy"), sec.getDouble(key + ".sell")));
                }
            }
        }

        var cs = pc.getConfigurationSection("categories");
        if (cs != null) {
            for (String id : cs.getKeys(false)) {
                List<Material> materials = new ArrayList<>();
                for (String name : cs.getStringList(id + ".items")) {
                    Material m = Material.matchMaterial(name);
                    if (m != null && prices.containsKey(m) && !materials.contains(m)) materials.add(m);
                }
                cats.put(id, materials);
            }
        }
    }

    String money(double value) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.forLanguageTag(
                plugin.getConfig().getString("settings.locale", "id-ID")));
        f.setMaximumFractionDigits(0);
        f.setMinimumFractionDigits(0);
        return f.format(value);
    }

    String pretty(Material m) {
        String[] parts = m.name().split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append(' ');
            out.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    ItemStack icon(Material m, String name, List<String> lore, String action, String item) {
        ItemStack stack = new ItemStack(m);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        if (item != null) meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, item);
        stack.setItemMeta(meta);
        return stack;
    }

    void openShop(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, "§b§lIMPERIAL SHOP");
        int slot = 0;
        for (var entry : cats.entrySet()) {
            if (slot >= 45) break;
            Material material = Material.matchMaterial(plugin.getConfig()
                    .getString("categories." + entry.getKey() + ".material", "CHEST"));
            String name = ChatColor.translateAlternateColorCodes('&',
                    plugin.getConfig().getString("categories." + entry.getKey() + ".name", entry.getKey()));
            inv.setItem(slot++, icon(material == null ? Material.CHEST : material, name,
                    List.of("§7Click untuk membuka kategori."), "category", entry.getKey()));
        }
        inv.setItem(49, icon(Material.BARRIER, "§cTutup", List.of(), "close", null));
        p.openInventory(inv);
    }

    void openCategory(Player p, String cat) {
        List<Material> list = cats.get(cat);
        if (list == null) return;

        Inventory inv = Bukkit.createInventory(null, 54, "§b§lSHOP » " + cat);
        for (int n = 0; n < Math.min(list.size(), 45); n++) {
            Material m = list.get(n);
            Price pr = prices.get(m);
            inv.setItem(n, icon(m, "§f§l" + pretty(m),
                    List.of("§7Beli: §eRp " + money(pr.buy()),
                            "§7Jual: §aRp " + money(pr.sell()), "",
                            "§aKlik untuk membeli"), "buy", m.name()));
        }
        inv.setItem(45, icon(Material.ARROW, "§eKembali", List.of(), "back", null));
        inv.setItem(49, icon(Material.BARRIER, "§cTutup", List.of(), "close", null));
        p.openInventory(inv);
    }

    void openSell(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, "§a§lSELL GUI");
        int slot = 0;
        Set<Material> seen = new HashSet<>();

        for (ItemStack stack : p.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir() || !prices.containsKey(stack.getType())
                    || !seen.add(stack.getType()) || slot >= 45) continue;

            Material m = stack.getType();
            Price pr = prices.get(m);
            inv.setItem(slot++, icon(m, "§f§l" + pretty(m),
                    List.of("§7Harga jual: §aRp " + money(pr.sell()),
                            "§7Jumlah inventory: §f" + count(p, m), "",
                            "§aKlik untuk menjual semua item ini"), "sell", m.name()));
        }

        inv.setItem(48, icon(Material.GOLD_INGOT, "§aJual Semua",
                List.of("§7Menjual semua item yang memiliki harga."), "sellall", null));
        inv.setItem(49, icon(Material.BARRIER, "§cTutup", List.of(), "close", null));
        inv.setItem(50, icon(Material.CHEST, "§eJual Item Tangan",
                List.of("§7Menjual seluruh stack di tangan."), "sellhand", null));
        p.openInventory(inv);
    }

    int count(Player p, Material m) {
        int total = 0;
        for (ItemStack stack : p.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == m) total += stack.getAmount();
        }
        return total;
    }

    void openQuantity(Player p, Material m) {
        if (m == null || !prices.containsKey(m)) return;
        quantities.putIfAbsent(p.getUniqueId(), 1);
        renderQuantity(p, m, quantities.get(p.getUniqueId()));
    }

    private void renderQuantity(Player p, Material m, int amount) {
        Price pr = prices.get(m);
        Inventory inv = Bukkit.createInventory(null, 27, "§b§lBELI » " + pretty(m));

        inv.setItem(10, icon(Material.REDSTONE_TORCH, "§c-16", List.of(), "qty:-16", m.name()));
        inv.setItem(11, icon(Material.REDSTONE, "§c-1", List.of(), "qty:-1", m.name()));
        inv.setItem(13, icon(m, "§f§l" + amount + "x",
                List.of("§7Harga total: §eRp " + money(pr.buy() * amount)), "none", m.name()));
        inv.setItem(15, icon(Material.GLOWSTONE_DUST, "§a+1", List.of(), "qty:+1", m.name()));
        inv.setItem(16, icon(Material.GLOWSTONE, "§a+16", List.of(), "qty:+16", m.name()));
        inv.setItem(22, icon(Material.EMERALD, "§aBeli " + amount + "x",
                List.of("§7Total: §eRp " + money(pr.buy() * amount)), "buy:selected", m.name()));
        inv.setItem(26, icon(Material.BARRIER, "§cKembali", List.of(), "backcat", m.name()));
        p.openInventory(inv);
    }

    void changeQuantity(Player p, Material m, int delta) {
        if (m == null || !prices.containsKey(m)) return;
        int current = quantities.getOrDefault(p.getUniqueId(), 1);
        int next = Math.max(1, Math.min(m.getMaxStackSize() * 9, current + delta));
        quantities.put(p.getUniqueId(), next);
        renderQuantity(p, m, next);
    }

    void clearQuantity(Player p) {
        quantities.remove(p.getUniqueId());
    }

    boolean buy(Player p, Material m, int amount) {
        Price pr = prices.get(m);
        if (pr == null || amount < 1) return false;

        double total = pr.buy() * amount;
        if (!economy.has(p, total)) return false;
        if (space(p, m) < amount) return false;

        EconomyResponse response = economy.withdrawPlayer(p, total);
        if (!response.transactionSuccess()) return false;

        Map<Integer, ItemStack> leftover = p.getInventory().addItem(new ItemStack(m, amount));
        if (!leftover.isEmpty()) {
            economy.depositPlayer(p, total);
            return false;
        }
        return true;
    }

    int space(Player p, Material m) {
        int total = 0;
        for (ItemStack stack : p.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) total += m.getMaxStackSize();
            else if (stack.isSimilar(new ItemStack(m))) total += m.getMaxStackSize() - stack.getAmount();
        }
        return total;
    }

    double sellMaterial(Player p, Material m) {
        if (m == null || !prices.containsKey(m)) return 0;
        return sellMatching(p, m);
    }

    double sellHand(Player p) {
        ItemStack stack = p.getInventory().getItemInMainHand();
        if (stack == null || stack.getType().isAir() || !prices.containsKey(stack.getType())) return 0;

        Material m = stack.getType();
        double total = prices.get(m).sell() * stack.getAmount();
        ItemStack backup = stack.clone();
        p.getInventory().setItemInMainHand(null);

        EconomyResponse response = economy.depositPlayer(p, total);
        if (!response.transactionSuccess()) {
            p.getInventory().setItemInMainHand(backup);
            return 0;
        }
        return total;
    }

    double sellContents(Player p) {
        double total = 0;
        List<SlotBackup> backup = new ArrayList<>();

        for (int slot = 0; slot < p.getInventory().getSize(); slot++) {
            ItemStack stack = p.getInventory().getItem(slot);
            if (stack == null || stack.getType().isAir() || !prices.containsKey(stack.getType())) continue;
            total += prices.get(stack.getType()).sell() * stack.getAmount();
            backup.add(new SlotBackup(slot, stack.clone()));
        }

        if (backup.isEmpty()) return 0;

        for (SlotBackup b : backup) p.getInventory().setItem(b.slot(), null);
        EconomyResponse response = economy.depositPlayer(p, total);
        if (!response.transactionSuccess()) {
            for (SlotBackup b : backup) p.getInventory().setItem(b.slot(), b.stack());
            return 0;
        }
        return total;
    }

    private double sellMatching(Player p, Material material) {
        double total = 0;
        List<SlotBackup> backup = new ArrayList<>();

        for (int slot = 0; slot < p.getInventory().getSize(); slot++) {
            ItemStack stack = p.getInventory().getItem(slot);
            if (stack == null || stack.getType() != material) continue;
            total += prices.get(material).sell() * stack.getAmount();
            backup.add(new SlotBackup(slot, stack.clone()));
        }

        if (backup.isEmpty()) return 0;
        for (SlotBackup b : backup) p.getInventory().setItem(b.slot(), null);

        EconomyResponse response = economy.depositPlayer(p, total);
        if (!response.transactionSuccess()) {
            for (SlotBackup b : backup) p.getInventory().setItem(b.slot(), b.stack());
            return 0;
        }
        return total;
    }

    record Price(double buy, double sell) {}
    record SlotBackup(int slot, ItemStack stack) {}
}
