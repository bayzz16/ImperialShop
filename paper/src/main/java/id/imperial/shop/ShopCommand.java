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
        if (!player.hasPermission("imperialshop.use")) {
            player.sendMessage("§cTidak memiliki izin.");
            return true;
        }
        shop.openShop(player);
        return true;
    }
}