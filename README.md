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

## Economy balance (Scarcity Economy v3)

This is a **scarcity-based starting balance**, not a guarantee of exploit-proof economics.

- Buy prices remain progression-based; sell prices are no longer a flat ~10% of buy price.
- Common terrain blocks and easily renewable materials have extremely low sell value (around 0.5–1% of buy price).
- Ordinary items/loot target around 2%; selected rare drops and endgame materials target around 6%.
- The intent is that a player with a mining power cannot become rich just by stripping huge areas of stone or cobblestone.
- **Price tuning alone cannot stop automated farms or high-power abilities.** Before a public launch, add server-side per-player sell quotas/cooldowns for common materials, and consider excluding basic terrain blocks from /sellall. Apply limits consistently to /sellall, /sellall <material>, /sellall hand, and GUI bulk selling.
- Confirm the actual unit multiplier for `shop-stack-size: 16` and non-stackable items in-game; the displayed price must match the number of items actually transacted.

Suggested staging test: give a test player a fast-mining power, mine and sell 10, 100, and 1,000 stacks of stone/cobblestone, then compare earnings with a player doing ordinary survival. Repeat with renewable farms and rare drops. If bulk mining is still the best money source, lower common-block sell prices further or disable their bulk selling. Do not call the economy production-ready until these tests pass.

## Shared MySQL/MariaDB economy (optional)

The default economy remains local `economy.yml`. To share balances between multiple Paper/Purpur backends, configure **each backend** to use the same MySQL/MariaDB database and table.

1. Create a dedicated database and a database user with only the permissions needed for the configured table (SELECT, INSERT, UPDATE, and CREATE if the plugin should create its table automatically). Restrict the user to the backend hosts where practical.
2. Configure `plugins/ImperialShop/config.yml` on every backend:

```yaml
economy:
  storage:
    mysql:
      enabled: true
      host: "DB_HOST"
      port: 3306
      database: "imperialshop"
      username: "imperialshop"
      password: ""
      table: "imperialshop_balances"
      pool-size: 5
```

3. Set `IMPERIALSHOP_DB_PASSWORD` in the environment of every backend process. Do not commit database credentials to Git. The JDBC connection requires TLS/SSL; configure the database endpoint and certificates accordingly.
4. Back up all existing `economy.yml` files and plan a reviewed migration before enabling shared storage. **Balances are not automatically imported**; the first connection creates the SQL table, not player balances.
5. Test on a staging server first: startup with the database available/unavailable, balance reads, deposits, withdrawals, insufficient funds, simultaneous transactions on two backends, restart persistence, and rollback/error behavior.

All backends must point at the same database/table to share balances. Keep the feature disabled until migration and staging checks are complete. Vault's economy API is synchronous, so database calls can block a server thread during slow database operations; use a reliable low-latency database and load-test before production. The SQL table uses `DECIMAL(20,2)`, whose maximum non-negative balance is `999999999999999999.99`.
