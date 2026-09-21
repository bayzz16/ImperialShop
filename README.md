# ImperialShop 2.1.0

ImperialShop is a custom Indonesian-style server shop system inspired by the workflow of EconomyShopGUI, implemented independently for Imperial X SOL.

## Supported targets
- Paper 1.21.1 as the primary compile target
- Purpur 1.21.x through Paper/Bukkit compatibility
- Velocity 3.x proxy bridge
- Java 21
- Vault economy provider

## Shop features
- Main category GUI
- Category pagination
- Item pagination
- Indonesian Rupiah-style economy display
- Buy and sell prices per material
- Left click: buy 1
- Right click: sell 1
- Shift-left: buy 64
- Shift-right: sell available quantity
- Middle click: custom quantity transaction screen
- Quantity +/- controls
- Custom buy and sell transaction buttons
- Sell GUI
- Sell all inventory
- Sell material
- Sell hand
- Per-category permissions
- Per-item permissions
- Rank/permission price modifiers
- Per-item maximum buy/sell amounts
- Configurable GUI titles, filler, sounds and messages
- Safe economy rollback if item delivery or payout fails
- Config reload
- Admin price editing
- Direct /shop <category> access

## Commands
- /shop
- /shop <category>
- /shop sell
- /sell
- /sellgui
- /sellall
- /sellall <material>
- /sellall hand
- /sellhand
- /imperialshop reload
- /imperialshop price <MATERIAL> <BUY> <SELL>
- /imperialshop info

## Build artifacts
- Paper/Purpur: paper/target/imperialShop.jar
- Velocity: velocity/target/imperialShop-velocity.jar

The GitHub Actions workflow performs a clean Maven build and explicitly verifies both artifact names before uploading them.

## Design boundary
ImperialShop is its own implementation. It uses the documented EconomyShopGUI feature set as a functional reference, but it does not depend on or copy EconomyShopGUI code.

## Configuration
The main configuration is YAML and is loaded through the Paper plugin configuration API. Edit plugins/ImperialShop/config.yml, then use /imperialshop reload.
