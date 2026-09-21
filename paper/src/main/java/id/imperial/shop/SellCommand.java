package id.imperial.shop;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class SellCommand implements CommandExecutor {
    private final ShopService shop;

    SellCommand(ShopService shop) {
        this.shop = shop;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Player only.");
            return true;
        }
        if (!shop.canSellGui(player)) {
            player.sendMessage("§cKamu tidak memiliki izin menggunakan SellGUI.");
            return true;
        }
        shop.openSellGui(player);
        return true;
    }
}