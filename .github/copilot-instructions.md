# GitHub Copilot Instructions

This repository is the `ai` Java/Spring AI dialogue service. Before suggesting or changing code, read `AGENTS.md` and `docs/ai-coding/README.md`.

Follow these project rules:

- Follow `docs/ai-coding/AI_DIRECTORY_STRUCTURE_GUIDE.md` before adding, moving, or deleting directories.
- Keep Java code under `src/main/java/com/ai`; tests belong under `src/test/java/com/ai`.
- Keep model selection through `AiChatClientProvider`; do not bind controllers directly to one provider SDK.
- Keep Dubbo RPC interfaces and DTOs in sibling `rpc-api`; this service only implements provider code or calls published contracts.
- Do not add trading, K-line, order, account, market, Discord, or Apple subscription directories to this service.
- Do not change existing model keys, database URLs, or production configuration values. Report file paths and line numbers only.
