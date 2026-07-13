# Lodestone RCON profile

Narrow transport adapter for Java-edition Minecraft RCON endpoints. It exposes only
`minecraft.command.rcon.execute`; command output remains explicitly unstructured text. Native
world, player, inventory, UI, input, and event capabilities stay unavailable because RCON cannot
provide their semantics safely.

Required environment:

- `LODESTONE_RCON_HOST` (default `127.0.0.1`)
- `LODESTONE_RCON_PORT` (default `25575`)
- `LODESTONE_RCON_PASSWORD` (required; never logged)
- `LODESTONE_TOKEN` (required MCP loopback token; never logged)

Optional:

- `LODESTONE_MCP_PORT` (default `37821`)
- `LODESTONE_RCON_MAX_OUTPUT_BYTES` (default `262144`)

Run the external gateway with `gradlew.bat :gateway:rcon-launcher:run --no-daemon`. The target
server must have RCON enabled and must be reachable before the adapter is registered as
available.
