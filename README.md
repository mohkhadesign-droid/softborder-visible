# Soft Borders Visible 2.0.0 — the storm

A **client-side add-on** that turns Soft Borders' dangerous area into a visible violet storm, with
active beacons carving clear shelters inside it. Minecraft 1.16.5 / Forge 36.2.34.

**Soft Borders itself is not modified.** You keep running `softborder-1.5.2.jar` exactly as it is,
on the server and on clients. This add-on goes into clients only.

Add-on for [Soft Borders](https://github.com/Jackiecrazy/softborder) by Jackiecrazy and DarkMega,
MIT. The geometry it draws was derived by reading that MIT source; this add-on is MIT too.

> **You can see the storm from safety.** That was the core requirement, and it is why the storm is
> world geometry with a long fade-in distance (96 blocks by default) rather than a screen overlay
> that only appears after you cross the line.

---

## 1. The actual geometry — and why it dictates the shapes

I read `BorderUtils` rather than assuming. Two findings drive everything:

### The main border is an unbounded vertical cylinder on the world axis

```java
BlockPos at = BlockPos.ZERO.add(0, p.getPosY(), 0);
double result = at.distanceSq(p.getPosX(), p.getPosY(), p.getPosZ(), false) / (border * border);
```

The emitter is rebuilt **every tick at x = 0, z = 0, y = floor(player y)**. World spawn is never
consulted. Because the emitter tracks the player's own y, the vertical term collapses to the
fractional part of their y — under one block — so the safe region is a **vertical cylinder of radius
R around the world axis, unbounded in y**.

So the storm wall is drawn as a tall band centred on your eye level that **fades out softly at the
top and bottom, with no cap**. This is deliberate and required by the brief: protection does *not*
end at any height, so a ceiling would be a lie. What you see is a viewing window onto an infinite
wall, not its extent. `wall height` (default 160) sets how much of it is drawn.

The one simplification: the true surface is a sphere recentred each tick, so its horizontal radius
is `sqrt(R² − frac(y)²)` — at most `1/(2R)` blocks tighter than the cylinder (0.05 blocks at R = 10,
0.0001 at R = 5000). Drawing the cylinder keeps the wall still instead of breathing with your y. It
is sub-block at every radius and cannot mislead you about where penalties begin.

### A beacon shelter is genuinely a sphere, so a dome is honest

```java
int coverage = (beacon.getLevels() + 1) * 10;
double dist = p.getDistanceSq(Vector3d.copyCentered(beacon.getPos())) / (coverage * coverage);
```

True 3D distance, so protection is a **sphere** of `(levels + 1) * 10` blocks (20/30/40/50) centred
on the beacon block. The brief asked me to render a cylinder if protection were an infinite-height
circle, and a dome only if it is genuinely vertically limited. **It is genuinely limited** — fly high
enough above a beacon and Soft Borders stops protecting you — so a closed sphere is correct and its
ceiling is not a false implication.

The radius number coincides with vanilla's beacon range, but **vanilla applies its potion effect in
an axis-aligned box with a stretched y range**. The shapes are different, and the shelter uses Soft
Borders' sphere. Beacons with `getLevels() == 0` are not emitters and get no shelter.

### Which area you are measured against

`getClosestBorderEmitter` picks the emitter minimising `distanceSq / radius²` — *normalised*
distance — then `getOverreachPercentage` divides by `getMaxBorderSize`, which is **not always the
same radius**: a beacon standing on the axis (`x == 0 && z == 0`) returns
`max(playerBorder, coverage)`. `SafeArea` carries both numbers, and `SafeAreaSet.select()`
reproduces the original loop including its tie-breaking.

**One upstream inconsistency**, worth knowing: beacon *selection* measures to the block **centre**
(`copyCentered`), while `getOverreachPercentage` measures to the raw block **corner**. They disagree
by up to 0.87 blocks. The shelter is drawn at the corner-centred sphere, since that is what drives
penalties, so at the very rim there is a sub-block band where the drawn surface and the true
switchover differ by less than one block. That is upstream behaviour; I left the gameplay maths
untouched rather than "fixing" it and changing how your server plays.

### One calculation for everything

The wall, the fog, the particles, the sound and the HUD all go through the same
`SafeAreaSet.select()` + `SafeArea.overreach()` the server uses. There is no second approximation
anywhere, so the HUD cannot contradict the visuals and neither can contradict your penalties.

---

## 2. The storm wall

Four layers on the same boundary surface, which is what gives flat geometry apparent depth — a
single pass would just look like moving wallpaper:

| Layer | Texture | Scale | Drift | Blend |
|---|---|---|---|---|
| Cloud body | vanilla `environment/clouds.png` | 96 blocks/tile | slow, one way | normal |
| Mid clouds | same | 42 blocks/tile | faster, **opposite** way | normal |
| Fine mist | same | 19 blocks/tile | fastest | normal |
| Energy | vanilla `entity/beacon_beam.png` | 10×40 | streaking upward | **additive** |

Crossing drifts at different scales is what reads as swirling. `clouds.png` is a real cloud sheet
with transparent gaps, so terrain, buildings and players stay recognisable through it — not a flat
purple cylinder, and not a recoloured world-border grid.

On top of that, **per-vertex colour and alpha**, using `POSITION_COLOR_TEX`:

- a cheap two-sine turbulence field over (angle, height, time) modulates alpha and the violet↔blue
  mix, so the storm is never a uniform sheet;
- a smooth vertical fade kills the top and bottom 30% of the band, so it reads as a storm front
  rather than a cut cylinder;
- arc ends fade with distance instead of popping.

Other properties: two-sided (`disableCull`), writes no depth so it never occludes anything drawn
after it, `polygonOffset(-3,-3)` against z-fighting, and **occluded by terrain by default** —
mountains and buildings hide it, like the vanilla border. Set `see through terrain = true` if you
want it x-ray.

All textures are vanilla. Nothing new is registered, no resource pack, no shader.

### Shelters as pockets, and overlap

Protection is the **union** of your cylinder and every active beacon sphere, so what is drawn is the
**outer surface of that union**: any patch lying inside another safe area is dropped
(`SafeAreaSet.insideAnyExcept`). That is what makes a beacon read as a clear pocket carved out of the
storm, and it gets the overlap cases right by construction:

- a beacon fully inside your border draws **nothing** — there is no storm there to carve;
- a beacon straddling the border draws only the **cap that protrudes**, and the stretch of main wall
  it covers is cut away, leaving one continuous edge — a connected extension of safety;
- two overlapping shelters show **no wall in the lens between them**;
- no seams, because the turbulence field is continuous in angle and height.

The shelter wall stays storm-coloured but carries a cyan accent, so it reads as a different kind of
boundary without becoming a solid cyan ball. The beacon beam is untouched — nothing is drawn over it,
and the storm writes no depth.

### Lightning

Cosmetic only. A branching ribbon generated on the wall surface, geometry built **once per bolt** and
reused for its ~0.28 s life, additive, bright. At most one bolt at a time; the interval is floored at
1.5 s and jittered so flashes never fall into a rhythm; the envelope is a single smooth rise-and-decay
rather than repeated on/off, specifically to avoid strobing. `lightning enabled = false` turns it off
entirely, and `lightning intensity = 0` does too.

**It is a drawing.** It never spawns a `LightningBoltEntity`, never starts a fire, never damages a
player or mob, never charges a creeper, and sends nothing to the server.

---

## 3. Out in the storm

Everything here is driven by `exposure`, derived from `overreach` — the exact value Soft Borders
scales penalties by — eased about 8% per tick, so roughly a second to thicken and a second to clear.
Nothing snaps, and nothing is ever applied inside a protected region.

- **Fog** blends from whatever the world's fog was toward a dark violet-blue, capped at density 0.06
  so you can always see far enough to move and fight.
- **Screen-edge tint** is a 3×3 vertex-coloured grid with a fully transparent centre — no texture, and
  the middle of the screen stays completely readable. `screen edge tint = 0` disables it.
- **Particles** are vanilla `PORTAL` with occasional `WITCH` and `DRAGON_BREATH`, capped at 12 per
  tick, and **each candidate position is tested against the safe union and skipped if inside** — so
  no storm particles ever appear in a shelter.
- **Wind and thunder**: vanilla's elytra wind-rush sample pitched down and quiet as a wind bed, and
  `ENTITY_LIGHTNING_BOLT_THUNDER` at low volume and pitch as muffled thunder when a bolt fires.
  `storm sound volume = 0` mutes everything this mod plays.

**Border Anchor takes priority.** Soft Borders drives fog itself while that effect is active, and
that fog is a gameplay signal. This add-on subscribes at `LOW` priority, checks whether the event was
already cancelled and whether the player has the effect, and keeps its hands off in either case.

Note Soft Borders also draws its own darkening vignette as you go further out; the purple tint is in
addition to it, which is why the default is a modest 0.5.

---

## 4. Beacon activation and removal

The beacon set is re-read every 10 ticks, so a pyramid change shows up within half a second.

A new shelter **fades in over 0.6 s — opacity only**. The geometry is at its true radius from the
first frame, so the visual never claims a smaller protected region than the server is enforcing.

Removal is **instant, deliberately**. A shelter wall lingering after protection ended would imply
safety that no longer exists, which the brief explicitly rules out. So there is no fade-out.

---

## 5. Install

| Where | What |
|---|---|
| **Server** | **Nothing.** Leave `softborder-1.5.2.jar` exactly as it is. No restart. |
| Clients that want the storm | `mods/sbvisible-2.0.0.jar`, alongside the existing `softborder-1.5.2.jar` |
| Clients that don't | Nothing. |

Both jars must be on the client — the add-on reads Soft Borders' API. Don't replace anything.

**The mods folder is your modpack's instance folder, not `.minecraft`.** CurseForge app: right-click
the pack → Open Folder → `mods`. Prism/MultiMC: instance → Folder → `.minecraft/mods`.

### Versions and mismatches

- **Soft Borders**: unchanged at 1.5.2, on server and clients, exactly as you have it now. Its
  network protocol, config, world data, player data and all four `/softborder` subcommands are
  untouched because the mod is untouched.
- **This add-on**: clients only, and **there is no version handshake at all**. It registers no
  network channel and no registry objects, so it cannot cause a Forge mismatch. Players with it and
  players without it can play together on the same server, and two clients on different add-on
  versions never interact — nothing is synchronised between clients.
- If you later drop this jar on a dedicated server by mistake, it loads, does nothing, and does not
  crash. See *Server safety*.

---

## 6. Configuration — `config/sbvisible-client.toml`

Created on first run. Soft Borders' own `softborder-server.toml` is never read or written.

### `[storm]`

| Key | Default | Meaning |
|---|---|---|
| `storm enabled` | `true` | Master switch for the storm wall |
| `reduced effects` | `false` | Weak machines: 2 cloud layers instead of 4, coarser geometry, no lightning, far fewer particles |
| `storm opacity` | `0.55` | Peak opacity. Lower it if the storm hides too much of the world beyond |
| `visibility distance` | `96.0` | Blocks at which the storm starts to fade in — **how far away you can see it from safety** |
| `max render distance` | `192.0` | Hard cutoff. Lower this first for frames |
| `wall height` | `160.0` | Height of the drawn band, centred on your eyes, soft-faded at both ends |
| `cloud animation speed` | `1.0` | 0 freezes the storm |
| `cloud density` | `1.0` | Murkiness of the cloud layers |
| `see through terrain` | `false` | Draw the storm through blocks |
| `storm colour` | `7B2FBE` | Deep violet. `RRGGBB`, `#RRGGBB` or `0xRRGGBB` |
| `storm accent colour` | `4A6CF0` | Storm blue, woven through and used for energy streaks |
| `beacon shelter accent colour` | `33E0FF` | Cyan accent on shelter boundaries |

### `[lightning]`

| Key | Default | Meaning |
|---|---|---|
| `lightning enabled` | `true` | Cosmetic flashes |
| `lightning interval seconds` | `6.0` | Average gap. Floored at 1.5 s to prevent strobing |
| `lightning intensity` | `1.0` | Brightness and size. 0 is invisible |

### `[atmosphere]`

| Key | Default | Meaning |
|---|---|---|
| `fog enabled` | `true` | Thicker fog out in the storm |
| `fog strength` | `1.0` | Internally capped so you can always move and fight |
| `screen edge tint` | `0.5` | 0 disables |
| `particles enabled` | `true` | Drifting storm particles, never inside a safe area |
| `particle density` | `1.0` | Capped per tick |
| `storm sound volume` | `0.4` | **0 mutes all sound from this mod** |

### `[hud]`

| Key | Default | Meaning |
|---|---|---|
| `show hud` | `true` | *Sheltered* / *Beacon shelter* / *In the storm* |
| `show distance` | `true` | Distance to the edge you are measured against |
| `hud x` / `hud y` | `4` / `4` | Offset in scaled pixels |

### Keybind

**Toggle storm**, category **Soft Borders Visible**, **unbound by default** so it cannot collide with
your modpack. Bind it under Options → Controls. It flips a session-only flag, so `storm enabled`
stays your startup default.

---

## 7. Multiplayer and server safety

**The server stays authoritative and is not modified.** It keeps deciding every penalty with its own
`BorderUtils`. This add-on is read-only and cosmetic: it cannot grant or remove protection, and it
sends nothing to the server, so there is nothing for a client to lie about.

Where its data comes from:

| Needed | Source |
|---|---|
| Your border radius, per dimension | `BorderData.getCap(player)` → `IBorderTracker.getBorderFor(world)`, both **public**. Soft Borders already syncs that capability on login, respawn, dimension change and after a `/softborder` change — so this is the server's value, and per-player radii are correct automatically. |
| Whether beacons protect | `BorderConfig.beacon`, a **public static** field. Soft Borders registers that spec as `ModConfig.Type.SERVER`, so Forge syncs it to the client at login — the server's setting, not a local guess. |
| Beacon positions and levels | Ordinary vanilla tile entities in loaded chunks. |

No reflection, no Mixin, no access transformer, no coremod. I verified this by compiling the add-on
against the **unmodified 1.5.2 sources**.

**Honest limits of the client-only approach**, since the brief assumed a both-sides mod:

1. **No new synchronisation exists.** The radius is already synced by Soft Borders; the beacon set is
   derived locally. The trade is up to half a second of lag after a pyramid changes, and a dependence
   on `BeaconTileEntity.getLevels()` being populated client-side — see *Validation*.
2. **Only your own radius is visible to you.** A client cannot see other players' radii. That is
   fine: the storm only ever shows *your* safe region, which is the only one that matters to you.
3. Beacons in unloaded chunks have no tile entity, so they are absent — the same condition the
   server-side penalty code already works under.

### Dedicated-server isolation

Audited by **bytecode analysis**, per class and per method, including method **parameter** types —
because Forge resolves a `@SubscribeEvent` method's event parameter when it registers the listener,
which is exactly how an ungated subscriber kills a server.

**Result: PASS.** The only classes reachable without a physical client are `SoftBorderVisible` (the
`@Mod` class) and `VisualConfig`, and neither references any client-only namespace. There are **zero**
crossings into client code from server-reachable code.

Everything touching `net.minecraft.client` or `com.mojang.blaze3d` lives in `sbvisible.client`, and
every event subscriber there is `@Mod.EventBusSubscriber(value = Dist.CLIENT)`, which FML reads from
the mod file scan without loading the class.

---

## 8. Gameplay changes

**None.** No penalty, radius, command, config key, capability format or world-data format is altered,
because Soft Borders is not altered. The add-on only draws, plays sound, and reads public state.

Beacon protection still means exemption from Soft Borders penalties only. Nothing here adds
protection from mobs, PvP, explosions or block damage, and no storm wall blocks movement or
projectiles.

---

## 9. Performance

- **Nothing is scanned per frame.** Beacons are re-read once every 10 ticks from an in-memory tile
  entity list; chunks are never scanned.
- **Geometry is bounded rather than cached in a VBO**, which is the deliberate choice: the arc is
  capped at 128 segments (64 reduced) × 10 vertical bands (5 reduced) × 4 layers (2 reduced), and
  sphere patches are individually distance-culled. That is a few thousand quads a frame — immediate
  mode handles it comfortably, and vanilla terrain rendering is orders of magnitude more. A VBO cache
  would add invalidation complexity for no measurable gain at these counts.
- **No per-frame allocation** in the wall path: the unit-sphere trig tables are `static final` and
  the loops use only locals. A bolt allocates its segment list once, every few seconds.
- If every patch is culled, no buffer is begun and no draw is issued.
- Far inside a large border nothing is drawn: two square roots and an early return.
- All GL state is restored in a `finally`, so a bad frame cannot leak state into other renderers.
- Particle count and lightning frequency are both hard-capped independently of config.
- **Pre-existing, not introduced here:** Soft Borders' own `getClosestBorderEmitter` still walks
  every loaded tile entity once per player per tick server-side when beacon sanctuary is on. That is
  1.5.2 behaviour; I left it alone because caching it would change which emitter wins within a tick.

### Shader and rendering mods — the main limitation

The storm uses the legacy `RenderSystem` + `Tessellator` path, the same one the vanilla world border
and Soft Borders' own vignette use. **OptiFine, Oculus/Iris and similar replace or reorder the
world-render pipeline**, so under a shader pack the storm may render unlit, in the wrong order against
translucent terrain (water, glass), or not at all. The fog override may also be ignored, since shader
packs usually implement their own fog. The HUD and screen tint are unaffected.

Nothing here *requires* OptiFine, Oculus or any shader pack. I could not test with them — see below.

---

## 10. Validation

### Performed here

| Check | Result |
|---|---|
| Upstream source and licence | MIT, `Jackiecrazy/softborder@290d4d7`; class list identical to the supplied 1.5.2 jar |
| Soft Borders' API reachable from another package | Confirmed by compiling against the **unmodified 1.5.2 sources** — no reflection needed |
| API names | Every Minecraft/Forge member verified against `Lothrazar/Cyclic` (1.16.5 / Forge **36.2.34** / MCP snapshot), `MinecraftByExample`, the MinecraftForge 1.16.x tree, and the MCP `methods.csv`/`fields.csv` tables — not from memory |
| Full type-check | All sources compile cleanly |
| **Real ForgeGradle build** | **Succeeds** against Forge 36.2.34 + MCP snapshot 20210309-1.16.5, with **zero compiler warnings or errors** |
| Reobfuscation | `reobfJar` ran; the shipping jar carries SRG names, so it loads outside a dev workspace |
| Jar contents | Only `sbvisible/` classes plus `mods.toml`, lang and `pack.mcmeta`. **No Soft Borders classes bundled** |
| Dedicated-server isolation | Bytecode audit with reachability from server roots, including parameter types: **PASS** |

### Still yours to run — everything needing a running game

I have no way to launch Minecraft here, so **nothing about how this actually looks has been
observed.** Specifically unverified:

1. **Whether the storm reads well** — layer scales, drift speeds, opacity and colour balance are
   judgement calls made blind. Expect to want `storm opacity`, `cloud density` and
   `visibility distance` tuned to taste. Tell me how it looks and I'll adjust the defaults.
2. **Whether beacon shelters appear at all.** They depend on `BeaconTileEntity.getLevels()` being
   populated client-side. Beacons tick and render on the client and `Levels` is written into the tile
   entity's update tag, so this should hold — but I could not prove it. **If the violet main wall
   appears and cyan shelters never do, that is the cause**, and the fix is moving beacon data to a
   server-synced packet. Everything else works regardless, since the radius comes from the synced
   capability.
3. **Fog interaction** with Border Anchor, weather, and other fog-touching mods.
4. **Shader packs**, per above.
5. **Frame cost** with several nearby beacons on real hardware.

---

## 11. Test checklist

### Dedicated server (do this first)

1. Start the server **unchanged**. Nothing about it should differ — this add-on was never installed
   there. Confirm `/softborder get`, `set`, `add`, `subtract` still work.
2. For belt-and-braces: drop `sbvisible-2.0.0.jar` into the server's `mods` once and confirm it
   reaches "Done" with no `NoClassDefFoundError` naming `net/minecraft/client`, `com/mojang/blaze3d`
   or `KeyBinding`. Then remove it — it does nothing there.

### Seeing the storm from safety — the headline requirement

3. `/softborder set <you> 64 minecraft:overworld`. Stand at x=0, z=0 and look outward. From ~96
   blocks away the violet storm should already be visible on the horizon, thickening as you approach.
4. Confirm it is **animated**: clouds drifting at different speeds in different directions, energy
   streaking upward, not a static sheet.
5. Confirm you can still **recognise terrain, buildings and players through it**.
6. Wait ~6 s near the wall for a lightning flash. It must not strobe, must not start a fire, and no
   lightning entity may appear. Set `lightning enabled = false` and confirm flashes stop.

### Crossing the boundary

7. Walk out through the wall. It must not impede movement; arrows and items pass freely.
8. Fog, purple screen-edge tint, particles and wind should ramp up **smoothly over about a second**,
   not snap. The HUD flips *Sheltered* → *In the storm* as penalties begin.
9. Walk back in. Everything should clear smoothly and completely.
10. Confirm you can still see well enough to fight out in the storm.
11. Fly straight up and down outside the border: the wall stays a vertical cylinder at constant
    radius with **no ceiling or floor**, confirming it is not a sphere.
12. Move world spawn far away and confirm nothing moves — the centre is x=0, z=0.

### Beacon shelters

13. Travel well beyond your border and build a 1-level beacon. Within ~half a second a shelter should
    open: storm cleared inside, cyan-accented boundary, fading in over about half a second.
14. Stand inside it: normal interior visibility, **no fog, no tint, no particles**, HUD reads
    *Beacon shelter* — and the storm still visible beyond the shelter boundary.
15. Walk out of the shelter: straight back into the storm. Walk back in: it clears again.
16. Add pyramid levels — the shelter should step 20 → 30 → 40 → 50 blocks within half a second each
    time. Break the base or obstruct the beam: the shelter must disappear **immediately**, with no
    lingering wall.
17. **Vertical test**: fly up above the beacon. Because Soft Borders' beacon protection really is
    spherical, you should pass out through the dome's top *and* start taking penalties at the same
    moment. The dome ceiling is truthful — verify the HUD agrees with the geometry there.
18. Confirm the beacon beam is still clearly visible through the shelter.

### Overlapping safe areas

19. Beacon **fully inside** your border: draws **nothing** — no misleading inner dome.
20. Beacon **straddling** the border: only the outside cap draws, the covered stretch of main wall is
    cut away, and the result is one continuous edge with no seam.
21. Two beacons whose spheres overlap: **no wall in the lens between them**, and no flickering.
22. Standing in any overlap region: no fog, no tint, no particles, HUD sheltered.

### Players, reconnect, dimensions

23. Two players, `/softborder set A 64` and `/softborder set B 256`, standing together. Each sees
    their own storm at their own radius. Change A's radius live: A's wall moves, B's does not.
24. Log out in the storm and back in: storm and HUD correct within half a second.
25. Nether and back: the radius is per dimension — confirm the storm matches the new dimension and
    no boundary from the old one lingers.
26. Die and respawn, including with `warpPercentage` set so Border Anchor triggers. **While Border
    Anchor is active, Soft Borders' own black fog must win** — the storm must not override it.

### Performance and accessibility

27. Set `reduced effects = true`: visibly simpler storm, no lightning, few particles. Check frames.
28. Lower `max render distance` and confirm the storm is culled closer in.
29. Stand near 3–4 active beacons at once and watch the frame time.
30. `storm sound volume = 0` mutes all wind and thunder. `screen edge tint = 0` removes the tint.
    `cloud animation speed = 0` freezes the storm.
31. If you run OptiFine or a shader pack, check whether the storm still renders.
