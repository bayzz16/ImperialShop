package id.imperial.shop;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
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

        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof ShopService.ShopHolder holder)) return;

        if (holder.type() == ShopService.GuiType.SEARCH) {
            int rawSlot = event.getRawSlot();
            if (rawSlot == 2) {
                event.setCancelled(true);
                ItemStack result = top.getItem(2);
                if (result != null && result.hasItemMeta()) {
                    String query = result.getItemMeta().getDisplayName();
                    query = org.bukkit.ChatColor.stripColor(query == null ? "" : query).trim();
                    if (!query.isBlank() && !query.equalsIgnoreCase("Ketik nama item...")) {
                        String finalQuery = query;
                        later(player, () -> shop.openSearch(player, finalQuery, 0));
                    } else {
                        player.sendMessage("§c✦ §fMasukkan nama item/material terlebih dahulu.");
                    }
                }
                return;
            }
            if (rawSlot == 0 || rawSlot == 1 || rawSlot == 3) return;
            event.setCancelled(true);
            return;
        }

        if (holder.type() == ShopService.GuiType.SELL_INPUT) {
            int rawSlot = event.getRawSlot();

            // Slots 0-44 are real SellGUI input slots. They must behave like a normal inventory.
            if (rawSlot >= 0 && rawSlot < 45) return;

            // Control bar is protected.
            if (rawSlot >= 45 && rawSlot < top.getSize()) {
                event.setCancelled(true);

                ItemStack clicked = event.getCurrentItem();
                if (clicked == null || !clicked.hasItemMeta()) return;

                var data = clicked.getItemMeta().getPersistentDataContainer();
                String action = data.get(shop.actionKey, PersistentDataType.STRING);

                if ("sellinput:all".equals(action)) {
                    double total = shop.sellInput(player);
                    if (total <= 0) {
                        player.sendMessage("§cTidak ada item valid yang dapat dijual.");
                    }
                    return;
                }

                if ("sellgui:list".equals(action)) {
                    later(player, () -> {
                        shop.returnSellInput(player);
                        if (shop.canSellGui(player)) shop.openSell(player);
                    });
                    return;
                }

                if ("close".equals(action)) {
                    later(player, () -> {
                        shop.returnSellInput(player);
                        player.closeInventory();
                    });
                    return;
                }

                if ("shop".equals(action)) {
                    later(player, () -> {
                        shop.returnSellInput(player);
                        shop.openShop(player);
                    });
                }
            }
            // Bottom inventory remains interactive for shift-clicking items into the input area.
            return;
        }

        event.setCancelled(true);

        ShopService.GuiType type = holder.type();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        var data = clicked.getItemMeta().getPersistentDataContainer();
        String action = data.get(shop.actionKey, PersistentDataType.STRING);
        String itemData = data.get(shop.itemKey, PersistentDataType.STRING);
        if (action == null || action.equals("none")) return;

        if (action.equals("confirm")) {
            later(player, () -> shop.confirmPending(player));
            return;
        }

        if (action.equals("cancel")) {
            shop.clearPending(player);
            later(player, () -> shop.openShop(player));
            return;
        }

        if (action.equals("close")) {
            later(player, player::closeInventory);
            return;
        }

        if (action.equals("shop")) {
            later(player, () -> shop.openShop(player));
            return;
        }

        if (action.equals("search")) {
            later(player, () -> shop.openSearchInput(player));
            return;
        }

        if (action.equals("favorites")) {
            later(player, () -> shop.openFavorites(player, 0));
            return;
        }

        if (action.equals("sellgui")) {
            if (shop.canSellGui(player)) {
                later(player, () -> shop.openSellGui(player));
            } else {
                player.sendMessage("§cKamu tidak memiliki izin menggunakan SellGUI.");
            }
            return;
        }

        if (action.equals("back")) {
            later(player, () -> shop.openShop(player));
            return;
        }

        if (action.equals("preview") && itemData != null) {
            String[] parts = itemData.split("\\|", 2);
            if (parts.length == 2) {
                Material material = Material.matchMaterial(parts[1]);
                if (material != null) later(player, () -> shop.openPreview(player, parts[0], material));
            }
            return;
        }

        if (action.startsWith("preview:") && itemData != null) {
            String[] parts = itemData.split("\\|", 2);
            if (parts.length == 2) {
                Material material = Material.matchMaterial(parts[1]);
                String actionType = action.substring("preview:".length());
                if (material != null) later(player, () -> shop.openQuantity(player, parts[0], material));
            }
            return;
        }

        if (action.equals("category") && itemData != null) {
            later(player, () -> shop.openCategory(player, itemData));
            return;
        }

        if (action.startsWith("catpage:")) {
            String[] parts = action.split(":", 3);
            if (parts.length == 3) {
                try {
                    int targetPage = Integer.parseInt(parts[2]);
                    later(player, () -> shop.openCategory(player, parts[1], targetPage));
                } catch (NumberFormatException ignored) {
                }
            }
            return;
        }

        if (action.startsWith("resultpage:")) {
            String[] parts = action.split(":", 3);
            if (parts.length == 3) {
                try {
                    String pageSource = new String(java.util.Base64.getDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8);
                    int targetPage = Integer.parseInt(parts[2]);
                    if (pageSource.equals("favorites")) {
                        later(player, () -> shop.openFavorites(player, targetPage));
                    } else if (pageSource.startsWith("search:")) {
                        later(player, () -> shop.openSearch(player, pageSource.substring("search:".length()), targetPage));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            return;
        }

        if (action.startsWith("mainpage:")) {
            try {
                int targetPage = Integer.parseInt(action.substring("mainpage:".length()));
                later(player, () -> shop.openShop(player, targetPage));
            } catch (NumberFormatException ignored) {
            }
            return;
        }

        if (action.equals("resultitem") && itemData != null) {
            String[] parts = itemData.split("\\|", 3);
            if (parts.length != 3) return;
            String source = parts[0];
            Material material = Material.matchMaterial(parts[2]);
            if (material == null) return;
            ShopService.Category category = shop.categories.get(parts[1]);
            if (category == null || !shop.canTrade(player, category, material)) return;

            ClickType click = event.getClick();
            if (click == ClickType.SHIFT_LEFT) {
                boolean added = shop.plugin.favorites().toggle(player, material);
                player.sendMessage(added ? "§d★ Ditambahkan ke favorit." : "§7☆ Dihapus dari favorit.");
                later(player, () -> {
                    if (source.equals("favorites")) shop.openFavorites(player, 0);
                    else if (source.startsWith("search:")) shop.openSearch(player, source.substring("search:".length()), 0);
                });
            } else if (click == ClickType.MIDDLE) {
                later(player, () -> shop.openQuantity(player, parts[0], material));
            } else if (click == ClickType.LEFT) {
                shop.buy(player, material, shop.shopDisplayAmount(material));
            } else if (click == ClickType.RIGHT) {
                shop.sellMaterial(player, material, shop.shopDisplayAmount(material));
            } else if (click == ClickType.SHIFT_RIGHT) {
                shop.sellMaterial(player, material, Integer.MAX_VALUE);
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
            // Normal clicks open an item preview; the preview then chooses BUY/SELL.
            // This keeps the shop readable and gives Bedrock/Geyser players a clear action path.
            int shopAmount = shop.shopDisplayAmount(material);
            if (click == ClickType.LEFT) {
                shop.buy(player, material, shopAmount);
            } else if (click == ClickType.RIGHT) {
                shop.sellMaterial(player, material, shopAmount);
            } else if (click == ClickType.MIDDLE) {
                later(player, () -> shop.openQuantity(player, parts[0], material));
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
                if (category != null) {
                    later(player, () -> shop.openQuantity(player, category.id(), material));
                }
            } else if (event.getClick() == ClickType.RIGHT) {
                shop.sellGuiMaterial(player, material, 1);
                later(player, () -> shop.openSell(player));
            } else {
                shop.sellGuiAllMaterial(player, material);
                later(player, () -> shop.openSell(player));
            }
            return;
        }

        if (action.startsWith("qty:set:")) {
            String value = action.substring("qty:set:".length());
            ShopService.QuantitySession session = shop.quantitySession(player);
            if (session != null) {
                if ("max".equalsIgnoreCase(value)) {
                    shop.setQuantity(player, session.max());
                } else {
                    try {
                        shop.setQuantity(player, Integer.parseInt(value));
                    } catch (NumberFormatException ignored) {
                    }
                }
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
            ShopService.QuantitySession session = shop.quantitySession(player);
            if (session != null) shop.buy(player, session.material(), shop.selectedQuantity(player));
            return;
        }

        if (action.equals("sell:selected")) {
            ShopService.QuantitySession session = shop.quantitySession(player);
            if (session != null) shop.sellMaterial(player, session.material(), shop.selectedQuantity(player));
            return;
        }

        if (action.equals("backcat")) {
            if (itemData != null) {
                later(player, () -> shop.openCategory(player, itemData));
            }
            return;
        }

        if (action.equals("sellhand")) {
            shop.sellHand(player);
            later(player, () -> shop.openSell(player));
            return;
        }

        if (action.equals("sellall")) {
            shop.sellContents(player);
            later(player, () -> shop.openSell(player));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof ShopService.ShopHolder holder)) return;

        if (holder.type() == ShopService.GuiType.SELL_INPUT) {
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot >= 45 && rawSlot < top.getSize()) {
                    event.setCancelled(true);
                    return;
                }
            }
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof ShopService.ShopHolder holder)) return;

        if (event.getPlayer() instanceof Player player) {
            if (holder.type() == ShopService.GuiType.QUANTITY) {
                shop.clearQuantity(player);
            }
            if (holder.type() == ShopService.GuiType.SELL_INPUT) {
                shop.returnSellInput(player);
            }
        }
    }
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        shop.cleanup(event.getPlayer());
    }

    private void later(Player player, Runnable task) {
        shop.plugin.getServer().getScheduler().runTask(shop.plugin, task);
    }
}

