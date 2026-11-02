# Soft Borders Visible 1.0.0

A **client-side add-on** that draws the edge of Soft Borders' safe areas. Minecraft 1.16.5 /
Forge 36.2.34.

Soft Borders itself is **not modified**. You keep running `softborder-1.5.2.jar` exactly as it is,
on the server and on clients. This add-on goes into clients only.

Add-on for [Soft Borders](https://github.com/Jackiecrazy/softborder) by Jackiecrazy and DarkMega,
MIT. The geometry it draws was derived by reading that MIT source; this add-on is MIT too.

---

## Why client-only works

Everything needed to draw your own boundary is already public and already on the client:

| Needed | Where it comes from |
|---|---|
| Your border radius for this dimension | `BorderData.getCap(player)` → `IBorderTracker.getBorderFor(world)` — both **public**. Soft Borders already syncs that capability to the client on login, respawn, dimension change and after a `/softborder` radius change. |
| Whether beacons protect at all | `BorderConfig.beacon`, a **public static** field. Because Soft Borders registers that spec as `ModConfig.Type.SERVER`, Forge syncs it to the client at login — so it is the server's setting. |
| Beacon positions and pyramid levels | Ordinary vanilla tile entities in loaded chunks. |

No reflection, no Mixin, no access transformer, no coremod. I verified this by compiling the add-on
against the **unmodified 1.5.2 sources**: the API is genuinely reachable from another package.

The radius is therefore still the server's authoritative number. The server keeps deciding every
penalty with its own `BorderUtils`; this add-on is read-only and cosmetic, so a wrong local reading
could only ever draw a slightly wrong wall — it can never grant or remove protection.

### What you give up versus a server-side build

1. **Beacon spheres can lag up to half a second** behind a pyramid being built or broken, because
   the add-on re-reads loaded beacons on a timer instead of being told. Harmless for a visual.
2. **It depends on `BeaconTileEntity.getLevels()` being populated client-side.** Beacons do tick and
   render on the client and `Levels` is written into the tile entity's update tag, so this should
   hold — but I could not run the game to prove it. If beacon spheres never appear while the amber
   main wall does, that is the cause, and the fix is the both-sides variant. **Everything else works
   regardless**, because the radius comes from the synced capability, not from a beacon.
3. **It can only know *your* radius**, not other players'. That is fine: the walls only ever show
   your own boundary anyway.

If reading Soft Borders ever throws — say a future release changes the capability — the add-on logs
one warning, switches rendering off for the session, and leaves your game alone.

---

## Install

| Where | What |
|---|---|
| Server | **Nothing.** Leave `softborder-1.5.2.jar` exactly as it is. No restart needed. |
| Clients that want walls | `mods/sbvisible-1.0.0.jar`, alongside the existing `softborder-1.5.2.jar` |
| Clients that don't | Nothing. |

No protocol version changes and no network channel is registered, so this cannot cause a Forge
handshake mismatch. Players with it and players without it can play on the same server at the same
time. Soft Borders' config, world data, player data and all four `/softborder` subcommands are
untouched because the mod is untouched.

Soft Borders must be present on the client (declared as a mandatory dependency) since the add-on
compiles against its API. If you drop this jar on a dedicated server by mistake it loads, does
nothing, and does not crash — see *Server safety* below.

---

## Configuration — `config/sbvisible-client.toml`

Created on first run. Soft Borders' own `softborder-server.toml` is never read or written.

### `[rendering]`

| Key | Default | Meaning |
|---|---|---|
| `render borders` | `true` | Master switch for the walls |
| `render main border` | `true` | Draw your own border's edge |
| `render beacon borders` | `true` | Draw nearby beacon safe areas (nothing shows if the server disabled beacon sanctuary) |
| `wall opacity` | `0.45` | Peak opacity, 0–1 |
| `fade in distance` | `48.0` | Blocks from a boundary at which its wall starts to fade in |
| `max render distance` | `128.0` | Hard cutoff; lower this first for frames |
| `wall height` | `64.0` | Total height of the main wall, centred on your eye level |
| `animate` | `true` | Scroll the texture |
| `main border colour` | `FFB000` | Amber. `RRGGBB`, `#RRGGBB` or `0xRRGGBB` |
| `beacon border colour` | `33CCFF` | Cyan |

### `[hud]`

| Key | Default | Meaning |
|---|---|---|
| `show hud` | `true` | Show the status indicator |
| `show distance` | `true` | Append distance to the edge |
| `hud x` | `4` | Offset from the left, scaled pixels |
| `hud y` | `4` | Offset from the top, scaled pixels |

### Keybind

**Toggle boundary walls**, category **Soft Borders Visible**, **unbound by default** so it cannot
collide with your modpack. Bind it under Options → Controls. It flips a session-only flag, so
`render borders` stays your startup default.

---

## What it draws

The geometry is taken from Soft Borders' own maths, not assumed. Two things are not obvious:

- **Your border is a vertical cylinder centred on x = 0, z = 0** — not a sphere, and not on world
  spawn. Soft Borders rebuilds the emitter every tick at `(0, floor(playerY), 0)`, which cancels the
  vertical term of the distance.
- **A beacon safe area is a sphere** of `(levels + 1) * 10` blocks around the beacon block. The
  radius number matches vanilla's beacon range, but vanilla applies its effect in a stretched *box*,
  so the shapes differ.

Only the **outer edge of the union** is ever drawn: a beacon fully inside your border draws nothing,
a beacon straddling it draws only the protruding cap, and overlapping beacons show one continuous
surface with no seam. A wall inside the safe union would be a line that does not separate safe from
unsafe.

The wall uses vanilla's own `textures/misc/forcefield.png`, tinted — the same scrolling grid as the
vanilla world border, so it reads as a boundary immediately and needs no new asset. It is two-sided,
see-through, writes no depth, does not block movement or projectiles, and restores all GL state in a
`finally`.

The HUD reads *Inside your border* / *Beacon sanctuary* / *Outside all safe areas*, and never writes
to chat. The optional distance is `|overreach| × size` — the straight-line distance to the surface of
the safe area Soft Borders is currently measuring you against. Where areas overlap that can
understate how far you are from danger, because a neighbour may reach further; it is deliberately the
honest reading of the value that actually governs your penalties.

For the full derivation, including an upstream sub-block inconsistency between how beacon selection
and beacon penalties measure distance, see `VISIBLE_BORDERS.md` in the modified-1.5.3 bundle.

---

## Building

There is **no compiled jar in this bundle** and I could not produce one: the environment I built
this in blocks `maven.minecraftforge.net`, `repo.spongepowered.org` and Maven Central, so Gradle
cannot even resolve the ForgeGradle plugin.

### Prerequisites

1. **JDK 8.** ForgeGradle 5.1 and the 1.16.5 toolchain require it.
2. **Gradle 7.x.** ForgeGradle 5.1 does not support Gradle 8, and this project ships no wrapper.
3. **Put `softborder-1.5.2.jar` into `libs/`.** The build compiles against its public API.

```
cp /path/to/softborder-1.5.2.jar libs/
gradle build
```

The jar lands in `build/libs/sbvisible-1.0.0.jar`.

### Or build it on GitHub Actions

`.github/workflows/build.yml` is included and pins Gradle 7.4.2 and Temurin 8, so you need nothing
installed. Push to a GitHub repo, then:

```
git add -f libs/softborder-1.5.2.jar    # .gitignore excludes libs/*.jar by default
git commit -m "add Soft Borders jar for the build"
git push
```

The jar appears under the run's **Artifacts** as `sbvisible-jar`.

To create a wrapper so `./gradlew` works afterwards: `gradle wrapper --gradle-version 7.4.2`.

---

## Server safety

Audited by bytecode analysis, per class and per method, including method **parameter** types —
because Forge resolves a `@SubscribeEvent` method's event parameter when it registers the listener,
which is exactly how an ungated subscriber kills a dedicated server.

Result: **PASS.** The only classes reachable without a physical client are `SoftBorderVisible` (the
`@Mod` class) and `VisualConfig`, and neither references any client-only namespace. There are **zero**
crossings into client code from server-reachable code — this build does not even need a
`DistExecutor` hop, because it registers no packet handler.

Everything that touches `net.minecraft.client` or `com.mojang.blaze3d` lives in `sbvisible.client`,
and every event subscriber there is `@Mod.EventBusSubscriber(value = Dist.CLIENT)`, which FML reads
from the mod file scan without loading the class.

---

## Validation

### Done here

| Check | Result |
|---|---|
| Soft Borders' API is reachable from another package | Confirmed by compiling this add-on against the **unmodified 1.5.2 sources** — no reflection needed |
| Type-check | All sources compile cleanly against stubs built from signatures verified in real 1.16.5 projects (`Lothrazar/Cyclic` on 1.16.5 / Forge 36.2.34, `MinecraftByExample`, the MinecraftForge 1.16.x tree, MCP `methods.csv`/`fields.csv`) |
| Server isolation | Bytecode audit with reachability from server roots: **PASS**, zero client crossings |
| Resource files | `mods.toml`, `pack.mcmeta` and the lang file parse |

### Still yours to run

A real ForgeGradle build, and everything that needs a running game: rendering appearance, the
client-side beacon-levels question above, frame cost, and shader-mod interaction.

**Shader and rendering mods are the main limitation.** The wall uses the legacy
`RenderSystem` + `Tessellator` path, like the vanilla world border. OptiFine, Iris and similar
replace or reorder the world-render pipeline, so under a shader pack the wall may render unlit, in
the wrong order against translucent terrain, or not at all. The HUD is unaffected.

---

## Test checklist

Since the server is untouched, start with the client:

1. Launch a client with `softborder-1.5.2.jar` **and** `sbvisible-1.0.0.jar`. Both should appear in
   the mod list with no errors.
2. Join your server. A client **without** the add-on must still connect normally — confirm no
   handshake complaint either way.
3. `/softborder set <you> 32 minecraft:overworld`. Walk out from x=0,z=0: the amber wall fades in
   around 48 blocks out and reads the same from both sides.
4. Cross it. The HUD flips *Inside your border* → *Outside all safe areas* as the penalties begin,
   and the distance passes through 0 at the wall.
5. Fly up and down: the wall stays a vertical cylinder at constant radius, confirming it is not a
   sphere.
6. Move world spawn far away and confirm nothing moves — the centre is x=0, z=0.
7. Walk through it: no impediment to movement, arrows and items pass freely.
8. Build a 1-level beacon outside your border: a cyan sphere of radius 20 appears within ~half a
   second. Add levels → 30, 40, 50. Break the base → it disappears. *(If spheres never appear, see
   limitation 2 above.)*
9. Place a beacon well **inside** your border: it should draw **nothing**.
10. Place a beacon **straddling** the main wall: only the outside cap draws, and the covered stretch
    of main wall is cut away, leaving one continuous edge.
11. Two overlapping beacons: no surface in the lens between them.
12. Log out beyond your border and back in: walls and HUD correct within half a second.
13. Nether and back: the radius is per dimension — confirm the wall matches and no old boundary
    lingers.
14. Die and respawn, then `/softborder set <you> 256`: the wall moves without a relog.
15. Toggle `render borders`, `show hud`, `animate`, both colours and `wall opacity`. Bind the toggle
    key and press it: walls vanish/return with an action-bar message, no chat spam.
16. Set `max render distance` low and confirm the wall is culled closer in.
17. If you run OptiFine or a shader pack, check the wall still appears.
