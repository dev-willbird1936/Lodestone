# ADR 0001: Gateway and native adapters are separate processes or boundaries

## Decision

Keep MCP-facing logic outside version-specific native integrations. Native adapters expose only
the adapter API and protocol contracts.

## Consequences

Loader changes do not require MCP API rewrites. Native APIs cannot leak into generic tools.
