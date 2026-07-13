# Versioning

Lodestone versions independently:

- wire protocol;
- capability contract;
- adapter artifact;
- Minecraft edition/version;
- loader/platform;
- MCP revision.

Optional unknown fields are tolerated. Required incompatible capability versions fail with a
structured incompatibility error. Published contract changes require an ADR and fixtures.
