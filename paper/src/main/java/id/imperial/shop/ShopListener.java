package id.imperial.shop;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public final class ShopListener implements Listener {
    final ShopService s;
    ShopListener(ShopService s) { this.s = s; }

    @EventHandler
    public void click(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        String title = ChatColor.stripColor(e.getView().getTitle());
        boolean shopGui = title.contains("IMPERIAL SHOP") || title.contains("SHOP »")
                || title.contains("BELI »") || title.contains("SELL GUI");
        if (!shopGui) return;

        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;

        ItemStack item = e.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;

        var pc = item.getItemMeta().getPersistentDataContainer();
        String action = pc.get(s.actionKey, PersistentDataType.STRING);
        String itemName = pc.get(s.itemKey, PersistentDataType.STRING);
        if (action == null) return;

        if (action.startsWith("qty:")) {
            Material m = Material.matchMaterial(itemName);
            int delta = Integer.parseInt(action.substring(4));
            s.changeQuantity(p, m, delta);
            return;
        }

        if (action.equals("sell")) {
            Material m = Material.matchMaterial(itemName);
            double total = s.sellMaterial(p, m);
            if (total > 0) {
                p.sendMessage("§aBerhasil menjual " + s.pretty(m) + ": §fRp " + s.money(total));
                s.openSell(p);
            } else p.sendMessage("§cTransaksi penjualan gagal atau item tidak tersedia.");
            return;
        }

        if (action.equals("sellhand")) {
            double total = s.sellHand(p);
            if (total > 0) {
                p.sendMessage("§aBerhasil menjual item tangan: §fRp " + s.money(total));
                s.openSell(p);
            } else p.sendMessage("§cItem di tangan tidak memiliki harga jual atau transaksi gagal.");
            return;
        }

        if (action.equals("sellall")) {
            double total = s.sellContents(p);
            if (total > 0) {
                p.sendMessage("§aBerhasil menjual inventory: §fRp " + s.money(total));
                s.openSell(p);
            } else p.sendMessage("§cTidak ada item yang dapat dijual atau transaksi gagal.");
            return;
        }

        if (action.equals("category")) { s.openCategory(p, itemName); return; }
        if (action.equals("back")) { s.openShop(p); return; }
        if (action.equals("close")) { s.clearQuantity(p); p.closeInventory(); return; }

        if (action.equals("buy")) {
            s.openQuantity(p, Material.matchMaterial(itemName));
            return;
        }

        if (action.equals("buy:selected")) {
            Material m = Material.matchMaterial(itemName);
            int amount = 1;
            try {
                var field = ShopService.class.getDeclaredField("quantities");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                var map = (java.util.Map<java.util.UUID, Integer>) field.get(s);
                amount = map.getOrDefault(p.getUniqueId(), 1);
            } catch (ReflectiveOperationException ignored) {}
            if (s.buy(p, m, amount)) {
                p.sendMessage("§aPembelian berhasil: §f" + amount + "x " + s.pretty(m));
            } else {
                p.sendMessage("§cSaldo tidak cukup atau inventory tidak memiliki ruang.");
            }
            return;
        }

        if (action.equals("backcat")) {
            Material m = Material.matchMaterial(itemName);
            if (m != null) {
                for (var entry : s.cats.entrySet()) {
                    if (entry.getValue().contains(m)) {
                        s.openCategory(p, entry.getKey());
                        return;
                    }
                }
            }
        }
    }

    @EventHandler
    public void drag(InventoryDragEvent e) {
        String title = ChatColor.stripColor(e.getView().getTitle());
        if (title.contains("IMPERIAL SHOP") || title.contains("SHOP »")
                || title.contains("BELI »") || title.contains("SELL GUI")) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void close(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) {
            String title = ChatColor.stripColor(e.getView().getTitle());
            if (title.contains("BELI »")) s.clearQuantity(p);
        }
    }
}
