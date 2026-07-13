# Architecture

Lodestone has four boundaries:

1. `protocol-model` — immutable, loader-neutral messages and capability definitions.
2. `adapter-api` — narrow service-provider contracts for native integrations.
3. `runtime-core` — sessions, policy, dispatch, timeout, cancellation, events, and audit metadata.
4. `mcp-server` — MCP-facing gateway; no native loader imports.

```text
MCP client -> MCP gateway -> runtime core -> adapter API -> version-specific native adapter -> Minecraft
```

Loader distributions add a separate host module around that adapter:

```text
loader host -> runtime/gateway -> pure native adapter -> Minecraft
```

The host is the composition boundary; the adapter itself never depends on `gateway/mcp-server`.

Adapters never depend on gateway implementation. Native APIs do not escape adapter boundaries.
