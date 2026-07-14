# mcp-server

Loader-neutral MCP gateway. It exposes tools and resources, accepts JSON-RPC notifications, and
supports bounded session-scoped event subscriptions without importing native Minecraft or loader
APIs. Prompts and server-initiated notification delivery are not implemented; clients must use the
documented tools/resources subset.
