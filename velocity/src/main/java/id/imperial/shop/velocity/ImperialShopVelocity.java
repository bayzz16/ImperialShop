package id.imperial.shop.velocity;

import com.google.inject.Inject;import com.velocitypowered.api.command.CommandSource;import com.velocitypowered.api.command.SimpleCommand;import com.velocitypowered.api.event.Subscribe;import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;import com.velocitypowered.api.plugin.Plugin;import com.velocitypowered.api.proxy.ProxyServer;import com.velocitypowered.api.proxy.server.RegisteredServer;import com.velocitypowered.api.proxy.Player;import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;import net.kyori.adventure.text.Component;import org.slf4j.Logger;
import java.nio.file.*;import java.util.*;import org.yaml.snakeyaml.Yaml;

@Plugin(id="imperialshop",name="ImperialShop",version="1.0.0",authors={"Imperial X SOL"})
public final class ImperialShopVelocity{
 private final ProxyServer proxy;private final Logger logger;private String backend="survival";
 @Inject public ImperialShopVelocity(ProxyServer proxy,Logger logger){this.proxy=proxy;this.logger=logger;}
 @Subscribe public void init(ProxyInitializeEvent e){proxy.getCommandManager().register("shop",new ForwardCommand(proxy,this));proxy.getCommandManager().register("sell",new ForwardCommand(proxy,this));logger.info("ImperialShop Velocity bridge enabled.");}
 RegisteredServer target(){return proxy.getServer(backend).orElse(null);}
 static final class ForwardCommand implements SimpleCommand{final ProxyServer p;final ImperialShopVelocity plugin;ForwardCommand(ProxyServer p,ImperialShopVelocity plugin){this.p=p;this.plugin=plugin;}public void execute(Invocation i){if(!(i.source() instanceof Player pl)){i.source().sendMessage(Component.text("Player only."));return;}RegisteredServer s=plugin.target();if(s==null){pl.sendMessage(Component.text("Server shop sedang tidak tersedia."));return;}pl.createConnectionRequest(s).fireAndForget();pl.sendMessage(Component.text("Membuka ImperialShop di server utama..."));}}
}
