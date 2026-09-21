package id.imperial.shop.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

@Plugin(id="imperialshop", name="ImperialShop", version="1.0.0", authors={"Imperial X SOL"})
public final class ImperialShopVelocity {
    private final ProxyServer proxy;
    private final Logger logger;
    private final String backend = "survival";
    @Inject public ImperialShopVelocity(ProxyServer proxy, Logger logger) { this.proxy=proxy; this.logger=logger; }
    @Subscribe public void init(ProxyInitializeEvent event) {
        proxy.getCommandManager().register("shop", new ForwardCommand(proxy,this));
        proxy.getCommandManager().register("sell", new ForwardCommand(proxy,this));
        logger.info("ImperialShop Velocity bridge enabled.");
    }
    RegisteredServer target() { return proxy.getServer(backend).orElse(null); }
    static final class ForwardCommand implements SimpleCommand {
        final ProxyServer proxy; final ImperialShopVelocity plugin;
        ForwardCommand(ProxyServer proxy, ImperialShopVelocity plugin){this.proxy=proxy;this.plugin=plugin;}
        public void execute(Invocation invocation){
            if(!(invocation.source() instanceof Player player)){invocation.source().sendMessage(Component.text("Player only."));return;}
            RegisteredServer server=plugin.target();
            if(server==null){player.sendMessage(Component.text("Server shop sedang tidak tersedia."));return;}
            player.createConnectionRequest(server).fireAndForget();
            player.sendMessage(Component.text("Membuka ImperialShop di server utama..."));
        }
    }
}