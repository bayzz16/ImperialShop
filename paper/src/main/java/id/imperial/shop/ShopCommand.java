package id.imperial.shop;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ShopCommand implements CommandExecutor {
    private final ShopService shop;

    ShopCommand(ShopService shop) {
        this.shop = shop;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cImperialShop hanya dapat digunakan oleh player.");
            return true;
        }
        if (!shop.canUse(player)) {
            player.sendMessage("§c✦ §fAkses ditolak §8• §7Kamu tidak memiliki izin.");
            return true;
        }

        if (args.length == 0) {
            shop.openShop(player);
            return true;
        }

        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "sell" -> shop.openSell(player);
            case "favorites", "fav" -> shop.openFavorites(player, 0);
            case "search" -> {
                String query = args.length >= 2
                        ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))
                        : "";
                shop.openSearch(player, query, 0);
            }
            case "help" -> sendHelp(player);
            default -> {
                var category = shop.categories.get(args[0].toLowerCase(java.util.Locale.ROOT));
                if (category != null) {
                    shop.openCategory(player, category.id());
                } else {
                    player.sendMessage("§c✦ §fKategori tidak ditemukan §8• §7Gunakan §e/shop help §7untuk melihat semua perintah.");
                }
            }
        }
        return true;
    }

    private void sendHelp(Player player) {
        player.sendMessage("§8━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        player.sendMessage("§b§l IMPERIAL SHOP §8• §fPANDUAN");
        player.sendMessage("§7Shop ekonomi untuk membeli, menjual, mencari, dan menyimpan item favorit.");
        player.sendMessage("");
        player.sendMessage("§e§lPERINTAH UTAMA");
        player.sendMessage("§b/shop §8» §fBuka menu utama ImperialShop.");
        player.sendMessage("§b/shop <kategori> §8» §fBuka kategori item tertentu.");
        player.sendMessage("§b/shop sell §8» §fBuka daftar item yang dapat dijual.");
        player.sendMessage("§b/shop search <kata> §8» §fCari item berdasarkan nama/material.");
        player.sendMessage("§b/shop favorites §8» §fBuka daftar item favorit.");
        player.sendMessage("§b/shop help §8» §fTampilkan panduan ini.");
        player.sendMessage("");
        player.sendMessage("§e§lPERINTAH JUAL");
        player.sendMessage("§b/sell §8» §fBuka Sell GUI untuk memasukkan item secara manual.");
        player.sendMessage("§b/sellgui §8» §fBuka GUI input penjualan item.");
        player.sendMessage("§b/sellhand §8» §fJual item yang sedang dipegang.");
        player.sendMessage("§b/sellall §8» §fJual semua item yang dapat dijual dari inventory.");
        player.sendMessage("§b/sellall <material> §8» §fJual semua item material tersebut.");
        player.sendMessage("§b/sellall hand §8» §fAlternatif menjual item di tangan.");
        player.sendMessage("");
        player.sendMessage("§e§lKONTROL SHOP");
        player.sendMessage("§fKlik kiri/kanan item §8» §7Buka preview item.");
        player.sendMessage("§fPreview §8» §7Pilih BUY atau SELL lalu atur jumlah.");
        player.sendMessage("§fKlik tengah §8» §7Buka pemilih jumlah.");
        player.sendMessage("§fShift + klik kiri §8» §7Beli 64 item.");
        player.sendMessage("§fShift + klik kanan §8» §7Jual semua item tersebut.");
        player.sendMessage("§fTransaksi besar §8» §7Dapat meminta konfirmasi sebelum diproses.");
        player.sendMessage("");
        player.sendMessage("§e§lCATATAN");
        player.sendMessage("§7Harga beli/jual, batas transaksi, kategori, dan akses item");
        player.sendMessage("§7ditentukan oleh konfigurasi shop. Gunakan §e/shop §7untuk mulai.");
        player.sendMessage("§8━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }
}
