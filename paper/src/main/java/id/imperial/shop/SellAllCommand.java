package id.imperial.shop;

import org.bukkit.command.*;
import org.bukkit.entity.Player;

public final class SellAllCommand implements CommandExecutor {
    final ShopService s;
    SellAllCommand(ShopService s) { this.s = s; }

    @Override
    public boolean onCommand(CommandSender c, Command cmd, String label, String[] args) {
        if (!(c instanceof Player p)) {
            c.sendMessage("Player only.");
            return true;
        }

        double total = s.sellContents(p);
        if (total > 0) {
            p.sendMessage("§aBerhasil menjual inventory: §fRp " + s.money(total));
        } else {
            p.sendMessage("§cTidak ada item yang dapat dijual atau transaksi gagal.");
        }
        return true;
    }
}
