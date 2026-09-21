package id.imperial.shop;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class SellAllCommand implements CommandExecutor {
    private final ShopService shop;

    SellAllCommand(ShopService shop) {
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

        if (args.length > 0 && args[0].equalsIgnoreCase("hand")) {
            double total = shop.sellHand(player);
            if (total > 0) {
                player.sendMessage("§aBerhasil menjual item tangan: §fRp " + shop.money(total));
            } else {
                player.sendMessage("§cItem di tangan tidak memiliki harga jual.");
            }
            return true;
        }

        if (args.length > 0) {
            Material material = Material.matchMaterial(args[0]);
            if (material != null) {
                double total = shop.sellMaterial(player, material, Integer.MAX_VALUE);
                if (total > 0) {
                    player.sendMessage("§aBerhasil menjual " + shop.pretty(material) + ": §fRp " + shop.money(total));
                } else {
                    player.sendMessage("§cItem tersebut tidak dapat dijual.");
                }
                return true;
            }
        }

        double total = shop.sellContents(player);
        if (total > 0) {
            player.sendMessage("§aBerhasil menjual inventory: §fRp " + shop.money(total));
        } else {
            player.sendMessage("§cTidak ada item yang dapat dijual.");
        }
        return true;
    }
}