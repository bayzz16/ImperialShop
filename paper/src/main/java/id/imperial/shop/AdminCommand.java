package id.imperial.shop;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public final class AdminCommand implements CommandExecutor {
    private final ImperialShopPlugin plugin;
    private final ShopService shop;

    AdminCommand(ImperialShopPlugin plugin, ShopService shop) {
        this.plugin = plugin;
        this.shop = shop;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("imperialshop.admin")) {
            sender.sendMessage("§cTidak memiliki izin.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage("§e/imperialshop reload");
            sender.sendMessage("§e/imperialshop price <MATERIAL> <BUY> <SELL>");
            sender.sendMessage("§e/imperialshop info");
            sender.sendMessage("§e/imperialshop stats [player]");
            sender.sendMessage("§e/imperialshop history [jumlah]");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadPlugin();
            sender.sendMessage("§aImperialShop berhasil di-reload.");
            return true;
        }

        if (args[0].equalsIgnoreCase("info")) {
            sender.sendMessage("§bImperialShop §f2.1.0");
            sender.sendMessage("§7Harga terdaftar: §f" + shop.prices.size());
            sender.sendMessage("§7Kategori: §f" + shop.categories.size());
            sender.sendMessage("§7Platform: §fPaper/Purpur 1.21.x");
            return true;
        }

        if (args[0].equalsIgnoreCase("stats")) {
            if (args.length >= 2) {
                org.bukkit.OfflinePlayer target = org.bukkit.Bukkit.getOfflinePlayer(args[1]);
                TransactionHistory.Stats stats = plugin.history().stats(target.getUniqueId());
                sender.sendMessage("§bStats §f" + target.getName());
                sender.sendMessage("§7Transaksi: §f" + stats.transactions);
                sender.sendMessage("§7Item: §f" + stats.items);
                sender.sendMessage("§7Nilai transaksi: §eRp " + shop.money(stats.money));
            } else {
                sender.sendMessage("§bGlobal transactions: §f" + plugin.history().totalTransactions());
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("history")) {
            int limit = 10;
            if (args.length >= 2) {
                try {
                    limit = Math.max(1, Math.min(20, Integer.parseInt(args[1])));
                } catch (NumberFormatException ignored) {
                    sender.sendMessage("§cJumlah history tidak valid.");
                    return true;
                }
            }
            sender.sendMessage("§b§lImperialShop History");
            for (TransactionHistory.Record record : plugin.history().recent(limit)) {
                sender.sendMessage("§7" + record.player() + " §f" + record.type()
                        + " §b" + record.amount() + "x " + record.material()
                        + " §7→ §eRp " + shop.money(record.total()));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("price") && args.length >= 4) {
            Material material = Material.matchMaterial(args[1]);
            if (material == null) {
                sender.sendMessage("§cMaterial tidak ditemukan.");
                return true;
            }

            try {
                double buy = Double.parseDouble(args[2]);
                double sell = Double.parseDouble(args[3]);

                if (!Double.isFinite(buy) || !Double.isFinite(sell) || buy < 0 || sell < 0) {
                    sender.sendMessage("§cHarga harus angka >= 0.");
                    return true;
                }
                if (sell > buy) {
                    sender.sendMessage("§cHarga SELL tidak boleh lebih besar dari BUY.");
                    return true;
                }

                plugin.getConfig().set("prices." + material.name() + ".buy", buy);
                plugin.getConfig().set("prices." + material.name() + ".sell", sell);
                plugin.saveConfig();
                shop.reload();

                sender.sendMessage("§aHarga " + material.name() + " diperbarui.");
                sender.sendMessage("§7Buy: §eRp " + shop.money(buy) + " §7| Sell: §aRp " + shop.money(sell));
                return true;
            } catch (NumberFormatException ex) {
                sender.sendMessage("§cHarga tidak valid.");
                return true;
            }
        }

        sender.sendMessage("§cArgumen tidak dikenal. Gunakan /imperialshop help");
        return true;
    }
}