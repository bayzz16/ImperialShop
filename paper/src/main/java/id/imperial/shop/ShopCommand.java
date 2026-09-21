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
            sender.sendMessage("Player only.");
            return true;
        }
        if (!shop.canUse(player)) {
            player.sendMessage("§cKamu tidak memiliki izin.");
            return true;
        }

        if (args.length == 0) {
            shop.openShop(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("sell")) {
            shop.openSell(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("favorites") || args[0].equalsIgnoreCase("fav")) {
            shop.openFavorites(player, 0);
            return true;
        }

        if (args[0].equalsIgnoreCase("search")) {
            String query = args.length >= 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : "";
            shop.openSearch(player, query, 0);
            return true;
        }

        if (args[0].equalsIgnoreCase("help")) {
            player.sendMessage("§b/shop §7- buka shop");
            player.sendMessage("§b/shop <kategori> §7- buka kategori");
            player.sendMessage("§b/shop sell §7- buka Sell GUI");
            player.sendMessage("§b/shop search <kata> §7- cari item");
            player.sendMessage("§b/shop favorites §7- buka favorit");
            return true;
        }

        var category = shop.categories.get(args[0].toLowerCase());
        if (category != null) {
            shop.openCategory(player, category.id());
        } else {
            player.sendMessage("§cKategori tidak ditemukan.");
        }
        return true;
    }
}