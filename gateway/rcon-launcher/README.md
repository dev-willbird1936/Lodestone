# Lodestone RCON launcher

Runs the loader-neutral Lodestone runtime and MCP loopback gateway around the RCON adapter.
The native adapter remains independent from MCP implementation code; this module is only the
process composition layer.

Run with:

```text
gradlew.bat :gateway:rcon-launcher:run --no-daemon
```

Set `LODESTONE_RCON_HOST`, `LODESTONE_RCON_PORT`, `LODESTONE_RCON_PASSWORD`, and
`LODESTONE_TOKEN` first. The launcher never logs either secret.
