package id.imperial.shop.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Plugin(id="imperialshop", name="ImperialShop", version="2.1.0", authors={"Imperial X SOL"}, description="ImperialShop proxy bridge for Velocity 3.x")
public final class ImperialShopVelocity {
    static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("imperialshop:bridge");
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Map<String,String> config = new HashMap<>();

    @Inject
    public ImperialShopVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy=proxy; this.logger=logger; this.dataDirectory=dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadConfig();
        proxy.getChannelRegistrar().register(CHANNEL);
        register("shop"); register("sell"); register("sellgui"); register("sellall"); register("sellhand");
        logger.info("ImperialShop 2.1.0 Velocity bridge enabled.");
    }

    private void register(String command) {
        if (Boolean.parseBoolean(config.getOrDefault("commands."+command,"true"))) {
            proxy.getCommandManager().register(command, new ForwardCommand(this, command));
        }
    }

    private void loadConfig() {
        try {
            Files.createDirectories(dataDirectory);
            Path file=dataDirectory.resolve("config.yml");
            if(!Files.exists(file)) Files.writeString(file, """
# ImperialShop Velocity Bridge
# Use the same secret on Velocity and every Paper/Purpur backend.
network-secret: "CHANGE-ME"
commands:
  shop: true
  sell: true
  sellgui: true
  sellall: true
  sellhand: true
messages:
  forwarded: "ImperialShop diteruskan ke server saat ini."
  unavailable: "Server saat ini belum siap menerima ImperialShop."
  not-configured: "Network secret ImperialShop belum dikonfigurasi."
""");
            var lines=Files.readAllLines(file);
            for(int i=0;i<lines.size();i++){
                String line=lines.get(i), trimmed=line.trim();
                if(trimmed.isEmpty()||trimmed.startsWith("#")||!trimmed.contains(":")) continue;
                int sep=trimmed.indexOf(':');
                String key=trimmed.substring(0,sep).trim();
                String value=trimmed.substring(sep+1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) value=value.substring(1,value.length()-1);
                if(line.startsWith("  ")){
                    String section=findSection(lines,i);
                    if(section!=null) config.put(section+"."+key,value);
                } else config.put(key,value);
            }
        } catch(IOException ex) { logger.error("Could not load ImperialShop Velocity config.",ex); }
    }

    private String findSection(java.util.List<String> lines,int index){
        for(int i=index-1;i>=0;i--){
            String trimmed=lines.get(i).trim();
            if(!lines.get(i).startsWith("  ")&&trimmed.endsWith(":")) return trimmed.substring(0,trimmed.length()-1);
        }
        return null;
    }

    String networkSecret(){
        String env=System.getenv("IMPERIALSHOP_NETWORK_SECRET");
        if(env!=null&&!env.isBlank()) return env;
        return config.getOrDefault("network-secret","");
    }

    static final class ForwardCommand implements SimpleCommand {
        private final ImperialShopVelocity plugin;
        private final String command;
        ForwardCommand(ImperialShopVelocity plugin,String command){this.plugin=plugin;this.command=command;}

        @Override public void execute(Invocation invocation){
            if(!(invocation.source() instanceof Player player)){
                invocation.source().sendMessage(Component.text("Player only.")); return;
            }
            String secret=plugin.networkSecret();
            if(secret.isBlank()||secret.equals("CHANGE-ME")){
                player.sendMessage(Component.text(plugin.config.getOrDefault("messages.not-configured","ImperialShop network secret belum dikonfigurasi."))); return;
            }
            Optional<ServerConnection> current=player.getCurrentServer();
            if(current.isEmpty()){
                player.sendMessage(Component.text(plugin.config.getOrDefault("messages.unavailable","Server saat ini belum siap menerima ImperialShop."))); return;
            }
            StringBuilder commandLine=new StringBuilder(command);
            for(String arg:invocation.arguments()) commandLine.append(' ').append(arg);
            try{
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(DataOutputStream out=new DataOutputStream(bytes)){ out.writeUTF(secret); out.writeUTF(commandLine.toString()); }
                if(!current.get().sendPluginMessage(CHANNEL,bytes.toByteArray())){
                    player.sendMessage(Component.text(plugin.config.getOrDefault("messages.unavailable","Server saat ini belum siap menerima ImperialShop."))); return;
                }
                player.sendMessage(Component.text(plugin.config.getOrDefault("messages.forwarded","ImperialShop diteruskan ke server saat ini.")));
            }catch(IOException ex){ player.sendMessage(Component.text("Gagal meneruskan perintah ImperialShop.")); }
        }
    }
}
