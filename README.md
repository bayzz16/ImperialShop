# ImperialShop

ImperialShop is a Minecraft shop and sell GUI plugin for Imperial X SOL.

## Platform support
- Paper 1.21.x
- Purpur 1.21.x (Paper-compatible server)
- Velocity 3.x proxy module
- Java 21
- Vault economy

## Commands
- /shop
- /sell
- /sellall
- /imperialshop reload

The Paper/Purpur module owns the inventory GUI and economy transactions. The Velocity module provides proxy-side command forwarding to the configured Minecraft backend server.

## Build
Run `mvn clean package`.
Artifacts are generated under the module target directories.