package id.imperial.shop;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class SellHandCommand implements CommandExecutor {
    private final ShopService shop;

    SellHandCommand(ShopService shop) {
        this.shop = shop;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Player only.");
            return true;
        }
        if (!player.hasPermission("imperialshop.use")) {
            player.sendMessage("§cTidak memiliki izin.");
            return true;
        }

        double total = shop.sellHand(player);
        if (total > 0) {
            player.sendMessage("§aBerhasil menjual item tangan: §fRp " + shop.money(total));
        } else {
            player.sendMessage("§cItem di tangan tidak memiliki harga jual atau transaksi gagal.");
        }
        return true;
    }
}