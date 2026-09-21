package id.imperial.shop;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public final class ShopListener implements Listener {
    private final ShopService shop;

    ShopListener(ShopService shop) {
        this.shop = shop;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        String title = ChatColor.stripColor(event.getView().getTitle());
        if (title.contains("IMPERIAL SELL GUI")) {
            if (event.getClickedInventory() == event.getView().getTopInventory()) {
                ItemStack clicked = event.getCurrentItem();
                var data = clicked != null && clicked.hasItemMeta()
                        ? clicked.getItemMeta().getPersistentDataContainer()
                        : null;
                String action = data == null ? null : data.get(shop.actionKey, PersistentDataType.STRING);

                if (event.getSlot() < 45) {
                    // Input slots are intentionally interactive.
                    return;
                }

                event.setCancelled(true);
                if ("sellinput:all".equals(action)) {
                    shop.sellInput(player);
                    shop.openSellGui(player);
                    return;
                }
                if ("sellgui:list".equals(action)) {
                    shop.openSell(player);
                    return;
                }
                if ("close".equals(action)) {
                    shop.returnSellInput(player);
                    player.closeInventory();
                    return;
                }
                if ("shop".equals(action)) {
                    shop.returnSellInput(player);
                    shop.openShop(player);
                    return;
                }
            } else {
                // Prevent taking items from the player's inventory while the sell GUI is open.
                event.setCancelled(true);
            }
            return;
        }
        if (!isShopGui(title)) return;

        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        var data = clicked.getItemMeta().getPersistentDataContainer();
        String action = data.get(shop.actionKey, PersistentDataType.STRING);
        String itemData = data.get(shop.itemKey, PersistentDataType.STRING);
        if (action == null) return;

        if (action.equals("none")) return;

        if (action.equals("close")) {
            shop.clearQuantity(player);
            player.closeInventory();
            return;
        }

        if (action.equals("shop")) {
            shop.clearQuantity(player);
            shop.openShop(player);
            return;
        }

        if (action.equals("sellgui")) {
            shop.clearQuantity(player);
            shop.openSell(player);
            return;
        }

        if (action.equals("back")) {
            shop.clearQuantity(player);
            shop.openShop(player);
            return;
        }

        if (action.equals("category") && itemData != null) {
            shop.openCategory(player, itemData);
            return;
        }

        if (action.startsWith("catpage:")) {
            String[] parts = action.split(":", 3);
            if (parts.length == 3) {
                try {
                    shop.openCategory(player, parts[1], Integer.parseInt(parts[2]));
                } catch (NumberFormatException ignored) {
                }
            }
            return;
        }

        if (action.startsWith("mainpage:")) {
            try {
                shop.openShop(player, Integer.parseInt(action.substring("mainpage:".length())));
            } catch (NumberFormatException ignored) {
            }
            return;
        }

        if (action.equals("item") && itemData != null) {
            String[] parts = itemData.split("\\|", 2);
            if (parts.length != 2) return;
            Material material = Material.matchMaterial(parts[1]);
            if (material == null) return;

            ShopService.Category category = shop.categories.get(parts[0]);
            if (category == null || !shop.canTrade(player, category, material)) {
                player.sendMessage("§cKamu tidak memiliki akses ke item ini.");
                return;
            }

            ClickType click = event.getClick();
            if (click == ClickType.MIDDLE) {
                shop.openQuantity(player, parts[0], material);
            } else if (click == ClickType.LEFT) {
                shop.buy(player, material, 1);
            } else if (click == ClickType.RIGHT) {
                shop.sellMaterial(player, material, 1);
            } else if (click == ClickType.SHIFT_LEFT) {
                shop.buy(player, material, 64);
            } else if (click == ClickType.SHIFT_RIGHT) {
                shop.sellMaterial(player, material, Integer.MAX_VALUE);
            }
            return;
        }

        if (action.equals("sell") && itemData != null) {
            Material material = Material.matchMaterial(itemData);
            if (material == null) return;
            if (event.getClick() == ClickType.MIDDLE) {
                ShopService.Category category = shop.categoryFor(material);
                if (category != null) shop.openQuantity(player, category.id(), material);
            } else if (event.getClick() == ClickType.RIGHT) {
                shop.sellMaterial(player, material, 1);
                shop.openSell(player);
            } else {
                shop.sellMaterial(player, material, Integer.MAX_VALUE);
                shop.openSell(player);
            }
            return;
        }

        if (action.startsWith("qty:")) {
            try {
                shop.changeQuantity(player, Integer.parseInt(action.substring(4)));
            } catch (NumberFormatException ignored) {
            }
            return;
        }

        if (action.equals("buy:selected")) {
            ShopService.QuantitySession session = quantitySession(player);
            if (session != null) shop.buy(player, session.material(), shop.selectedQuantity(player));
            return;
        }

        if (action.equals("sell:selected")) {
            ShopService.QuantitySession session = quantitySession(player);
            if (session != null) {
                shop.sellMaterial(player, session.material(), shop.selectedQuantity(player));
            }
            return;
        }

        if (action.equals("backcat")) {
            if (itemData != null) shop.openCategory(player, itemData);
            return;
        }

        if (action.equals("sellhand")) {
            shop.sellHand(player);
            shop.openSell(player);
            return;
        }

        if (action.equals("sellall")) {
            shop.sellContents(player);
            shop.openSell(player);
        }
    }

    private ShopService.QuantitySession quantitySession(Player player) {
        return shop.quantitySession(player);
    }

    private boolean isShopGui(String title) {
        return title.contains("IMPERIAL SHOP")
                || title.contains("SHOP »")
                || title.contains("TRANSAKSI »")
                || title.contains("SELL GUI");
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        String title = ChatColor.stripColor(event.getView().getTitle());
        if (isShopGui(title)) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            String title = ChatColor.stripColor(event.getView().getTitle());
            if (title.contains("TRANSAKSI »")) shop.clearQuantity(player);
            if (title.contains("IMPERIAL SELL GUI")) shop.returnSellInput(player);
        }
    }
}