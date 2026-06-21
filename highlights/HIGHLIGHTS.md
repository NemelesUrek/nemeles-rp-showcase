# Highlights — representative source from the larger private server

This folder is **for reading**, not building. These are the most interesting pieces of the
full NemelesRP server; the buildable, unit-tested core lives in the repo root
(`nemeles-core-api`, `nemeles-core`, `nemeles-jobs`). The full 30-module server stays private.

## Combat & 3D crossplay — [`nemeles-combat/`](nemeles-combat/)
- `.../render/GunModelService.java` — **Java ⇄ Bedrock parity for in-hand 3D item models**. Wraps the
  BetterModel API by **pure reflection** (soft-dependency, degrades gracefully) and hides the
  Java disguise from Bedrock players via Floodgate so they render their own client-side attachable.
- `.../body/BodyPart.java`, `.../body/BodyManager.java` — **15-part anatomical damage** located
  by vector math; a Project-Zomboid-style medical model (bleeding, fractures, infections).
- `.../GunListener.java` — hitscan ballistics with a **double raytrace** (blocks first, then
  entities capped to that distance) so bullets stop at walls.

## Local-LLM NPCs — [`nemeles-npcai/`](nemeles-npcai/)
- `.../ConversationManager.java` — assembles the LLM system prompt from **persona + live world
  context (alert, territory owner, wanted heat) + per-player affinity + long-term memory**.
- `.../OllamaClient.java` — async `java.net.http` client; **fail-safe null returns** so the game
  never blocks when the AI is offline; tuned sampling; a CJK language-drift guard.

## Turf war — [`nemeles-territories/`](nemeles-territories/)
- `.../TerritoryManager.java` — turf-war engine with an **anti-double-spend guard**.

## Crossplay tooling — [`resourcepack-scripts/`](resourcepack-scripts/)
- `build_vehicles.py` — reskins the **vanilla** Minecraft boat entity per wood-variant to render
  distinct 3D vehicles on Bedrock with **zero Java changes**.
- `build_combined_pack.ps1` — crossplay resource-pack packaging (forces forward-slash zip entries,
  flattens overlays, auto-syncs the pack SHA-1 into `server.properties`).
