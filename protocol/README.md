# Protocol

Protocol v1 uses JSON Schema 2020-12. It separates wire protocol version, capability contract
version, adapter version, Minecraft version, loader version, and negotiated MCP revision.

Unknown optional fields are tolerated. Incompatible required versions fail with structured errors.
