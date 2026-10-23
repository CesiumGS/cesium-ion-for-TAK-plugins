# VR-TAK (Phase 2): findings and plan

What the work PC shows about VR-TAK 5.0 (UE 5.3.2 source build at
`C:\Program Files\Epic Games\VR-TAK`) and how Cesium ion fits in. The PC
reports are on the `device-reports` branch (`runs/*-vrtak-*`). The one-off
investigation scripts behind them (vrtak-guide, unreal-inspect,
cesium-unreal-binary, vrtak-api, vrtak-engine-state and others) were removed
from `vrtak/scripts` in the October 2026 clean-up; they are in the git
history.

## The PC

- Visual Studio 2022 Professional (MSVC 14.36 and 14.44), Windows SDK
  10.0.26100, CMake, Python, .NET. 99 GB free on C:.
- The Windows Hypervisor Platform is present, so an Android emulator here
  would be accelerated (for ATAK Self-Hosted tests next to the ion server).
- The VR-TAK folder is owned by, and writable for, the user account.

## VR-TAK's engine is not fully built

`UnrealEditor-Core.dll` and `UnrealEditor-Launch.dll` are missing from
`Engine\Binaries\Win64` (463 other editor DLLs are there), so
`UnrealEditor.exe` and `UnrealEditor-Cmd.exe` cannot start
(STATUS_DLL_NOT_FOUND). For the same reason UnrealBuildTool wants to rebuild
engine modules before it builds any project.

The VR-TAK Plugin Developer Guide expects developers to build the engine
once: `GenerateProjectFiles.bat`, then in Visual Studio build
UnrealBuildTool, UnrealHeaderTool, and the UE5 project (Development Editor,
Win64). That writes into the VR-TAK folder under Program Files, so it waits
for the owner's go-ahead.

### Building the engine layer (approved)

**Status (20 October): built.** GameEditor finished with no errors:
`UnrealEditor.exe`, the engine modules, Cesium for Unreal 2.17
(CesiumRuntime, CesiumEditor) and the Cesium ion plugin (VTAK_CesiumIon).
(`Launch` is linked into `UnrealEditor.exe` in this build; there is no
`UnrealEditor-Launch.dll`.)

- `vrtak/scripts/vrtak-engine-build.ps1` builds `GameEditor` (engine, Game
  module and plugins) in place, detached, with Cesium for Unreal's source in
  `Game\Plugins`.
- It uses MSVC 14.36 (`-CompilerVersion=14.36.32532`, the toolset UE 5.3
  targets) and `-CompilerArguments=/wd4756`.
- The flag is needed because the PC's Windows SDK 10.0.26100 makes
  `RenderGraphPrivate.cpp` fail with error C4756 (overflow in constant
  arithmetic) on either toolset. Without it, engine source would have to be
  edited.
- The engine layer was never fully built: it is a full build of about 12,150
  actions, a few hours on this PC.
- `/wd4756` did not take effect, so `vrtak/scripts/vrtak-engine-fix.ps1`
  changes only the lines the build log reports with C4756 (INFINITY/NAN to
  `std::numeric_limits`; an infinite clamp bound to
  `TNumericLimits<float>::Max()`), with the originals backed up and listed in
  `%USERPROFILE%\cesium-vrtak\backup\manifest.txt`. So far:
  `RenderGraphPrivate.cpp` and `PeakPicker.cpp`.
- VR-TAK's targets build without unity batches or precompiled headers
  (`bUseUnityBuild = false`, `bUsePCHFiles = false`), so Cesium for Unreal
  files that rely on a batch-mate's includes fail; the fix task adds the
  missing include per file (UE 5.3 has no `ModuleRules.ForceIncludeFiles`).
- The Fab plugin does not build in this engine; it is disabled in
  `Game.uproject` (approved, backed up).
- Disk: the build output (`Engine\Intermediate`, 77 GB) was moved to
  `F:\VR-TAK-build\Engine\Intermediate` with a junction at the old path
  (approved; C: had 7 GB free). To undo: remove the junction, move the folder
  back.

## Cesium for Unreal

- **Release:** v2.17.0 is the last with UE 5.3 builds. It is downloaded to
  `%USERPROFILE%\cesium-vrtak\downloads`, both the source-only release
  (204 MB) and the binary release (1 GB).
- **BuildId:** the binary release's modules carry BuildId 27405482, the same
  as VR-TAK's engine, so they should load without compiling.
- **Not yet run:** loading them still needs the engine layer (above).
  The check (`cesium-unreal-binary.ps1`, now removed) used a content-only
  host project, editor headless, data cache in the user profile.
- **Source build:** building from source (`cesium-unreal-build.ps1`,
  `vrtak/host`) stopped at UBT's engine check, as intended
  (`-NoEngineChanges`). Only `Engine\Intermediate\Build\SourceFileCache.bin`
  was written.

## How a VR-TAK plugin works (guide and headers)

- **Location and dependencies:** a plugin lives in `Game/Plugins/<Name>`.
  Its `.uplugin` enables `VH_COT` and `VH_Plugins`. Build.cs depends on
  `Game`, `VH_Plugins` and `VH_Developer` (the `VH_*` modules are
  precompiled).
- **Entry point:** a subclass of `AVH_PluginActor`, spawned when the game
  starts. Its BeginPlay adds ribbon buttons and menus, and spawns actors.
- **UI:** UMG widgets opened through `UUI_Subsystem_Core`. One widget works
  on the desktop viewport, as a world-space panel in VR, or as a pop-out
  window. A plugin can also add context-menu options
  (`IInterface_UI_ContextMenuOption`) and tabs (`IInterface_UI_Tab`).
- **Build:** run `build_dev.bat`.
- **Globe:** `AVH_GlobeReferenceActor` uses WGS84 (the same ellipsoid as
  Cesium) and offers `GeodeticToWorldDouble`, `WorldToGeodeticDouble` and
  `GeodeticToECEF`. It has two modes:
  - ENU, a tangent plane at `GeoLocation`; this is the origin, moved by
    `SetGeodeticLocation`;
  - Ellipsoid, the whole globe.

  The globe can be scaled (`UnrealUnitsPerMeter` × actor scale). No
  origin-changed event is visible.
- **VR-TAK already has a tile stack** (`Game_TileManager`), including
  `Game_MatComponent_CesiumIon` (likely imagery) and
  `Game_MeshComponent_CesiumIon` (likely terrain), a Mapbox terrain source,
  and its own b3dm 3D Tiles loader. Only the file names are visible so far.

## VR-TAK's own Cesium ion support (Game module source)

- **Components:** `UGame_MatComponent_CesiumIon` (imagery) and
  `UGame_MeshComponent_CesiumIon` (quantized-mesh terrain) are tile sources
  of `UGame_TileManager`.
- **Endpoint:** they call `https://api.cesium.com/v1/assets/<id>/endpoint`,
  hardcoded, so there is no Self-Hosted support.
- **Settings:** one global asset ID each, from VR-TAK settings
  (`cesium_imagery.CesiumAssetID`, `cesium_heightmap.CesiumAssetID`), plus a
  token setting.
- **Imagery** must be ion-tiled TMS (`tilemapresource.xml`).
- **No 3D Tiles.** The tile model is one mesh plus one stitched image per
  tile, with no level-of-detail tree.
- **Plugin hooks:**
  - The tile manager is `GAME_API`, with
    `RegisterTileComponent(FTerrainSourceInfo)`,
    `AddActiveComponentID(id, bBroadcast)`, `ForceUpdate()` and
    `SetEnabled(bool)`.
  - Only one terrain (mesh) source can be active.
  - `ShouldStreamTiles` turns VR-TAK's tiles off.
- **Ribbon:**
  - Register with `UGame_Subsystem_Ribbon::Get()->RegisterButton(UUserWidget*)`.
    The widget is a `UGame_UserWidget_RibbonButton` with `ID`, `Text`, `Icon`,
    `DefaultTab`, `DefaultCategory` and `MenuClass`.
  - Panels open through `UUI_Subsystem_Core::OpenWithDefaultsByClass`, which
    works on the desktop, in VR (world-space) and in a pop-out window.
- **Bootstrap:** `VTAK_Example`'s module spawns its plugin actor
  (`AGame_PluginActor` subclass) in `OnPostWorldInitialization` and destroys
  it on world cleanup. This is the template.
- **Globe axes (ENU mode):** `AGame_GlobeReferenceActor_ENU` maps ENU to
  Unreal as **+X north, +Y east, +Z up, in centimetres**, then applies the
  actor's transform (rotation and scale, for tabletop view). Cesium for
  Unreal uses +X east, +Y south, +Z up, so the georeference needs a +90° yaw
  and the same transform.
- **Origin changes:** the tile manager re-syncs on the globe reference's
  location-changed handler and `FCoreDelegates::PostWorldOriginOffset`.

## Plan: hybrid

| Content | How | Why |
|---|---|---|
| 3D Tiles (ion, Self-Hosted) | Cesium for Unreal `ACesium3DTileset` under one plugin-owned `ACesiumGeoreference` | VR-TAK has no 3D Tiles streaming |
| Google Photorealistic 3D Tiles | Cesium for Unreal; turn VR-TAK's terrain tiles off while shown (`SetEnabled(false)` / `ShouldStreamTiles`), and show credits | It carries its own ground; two grounds z-fight |
| Terrain | VR-TAK's own stack (set `cesium_heightmap.CesiumAssetID`, activate the CesiumIon mesh source) | VR-TAK depends on its terrain: ground placement traces, DTED fallback, one active source |
| Imagery | On VR-TAK terrain: its CesiumIon imagery source (ion-tiled TMS only). On Cesium tilesets: `UCesiumIonRasterOverlay` | Matches whichever surface it drapes on |
| Self-Hosted terrain and imagery | Cesium for Unreal (VR-TAK's sources are fixed to api.cesium.com), or a plugin tile source registered with `RegisterTileComponent` | Needs a base-URL setting VR-TAK lacks |

Steps:

1. **Engine layer (needs the owner's OK).** Build VR-TAK's engine layer as
   the guide says, then load Cesium for Unreal (BuildIds already match).
   Done: see "Building the engine layer" above.
2. **Plugin `VTAK_CesiumIon`.**
   - The module spawns the plugin actor, as `VTAK_Example` does.
   - The actor registers a ribbon button ("Cesium ion", Cesium mark) that
     opens a UMG panel: the ATAK pane's design (brand colours, sign-in, your
     assets, Add to map, on your map).
3. **Georeference sync.**
   - Origin and transform: set the origin to the globe's `GeoLocation`,
     copy the globe actor's transform, and add the +90° yaw.
   - Re-sync when the globe location changes and on `PostWorldOriginOffset`.
   - Validate with `GeodeticToWorldDouble` against
     `TransformLongitudeLatitudeHeightPositionToUnreal` at test points.
   - Collision: put tilesets on the terrain collision channel, so ground
     placement and context menus work on them.
4. **Sign in with Cesium ion.**
   - The same OAuth client (2438) and PKCE flow as ATAK. Cesium for Unreal's
     ion session code is editor-only, so the plugin implements it, as the
     ATAK core does.
   - The token is stored per user (DPAPI) and also written to VR-TAK's ion
     token setting, so VR-TAK's own ion terrain and imagery work after
     sign-in.
5. **Self-Hosted.** Use the same settings model as ATAK, tested against the
   ion Self-Hosted install in WSL on the same PC.

## Placing Cesium content in VR-TAK's world (20 October)

VR-TAK has two globe actors that matter: the TOC's tabletop ENU map
(`Game_GlobeReferenceActor_ENU_0`, scale 0.01) and the world's full-size,
Earth-centred `Game_GlobeReferenceActor_Ellipsoid_0` (scale 1, the camera
about 6,370 km from the scene origin). The plugin first aligned its Cesium
georeference with the tabletop and kept that after `ce GoToWorld`, so every
layer loaded (diagnostics: 100%) but sat at the Earth's centre in the world.

The plugin now picks the set-up globe that puts the player nearest the
ground (`WorldToGeodeticDouble` of the pawn), and on the Earth-centred globe
anchors Cesium on the ground below the player, moving the anchor only after
about 5 km. In the world, test content now sits where expected (the
Philadelphia boxes 1.1 km from the camera) and Cesium terrain draws beyond
VR-TAK's own map. Near the viewer VR-TAK's map still draws over Cesium
terrain and imagery (they show when lifted 60 m with `-CesiumIonRaise`);
drawing Cesium on top, or hiding VR-TAK's map where Cesium has content, is
next.

Cloud sign-in on the PC: ion redirects to `http://127.0.0.1:38736/cesium-tak/oauth2/callback`,
which only answers while VR-TAK is running and waiting for that sign-in.
Run `vrtak-launch-cloud` (15 minutes) and sign in on the page it opens; a
sign-in page left over from an earlier run cannot connect. Choosing "log in
as a team" on ion's page signs in as that team (for example CesiumBD), and
the panel lists the team's assets.

## Decisions for the owner

- **Engine layer:** may the VR-TAK engine layer be built in place (writes
  under `C:\Program Files\Epic Games\VR-TAK`), or should VR-TAK be copied
  to the user profile first (about the same disk use as the install)?
- **Plugin location:** when the plugin exists, may it go into VR-TAK's
  `Game/Plugins` (also under Program Files), as the guide expects?
- **ATAK Self-Hosted tests on the PC:** these need an Android emulator there
  (approved) and the ATAK SDK. The SDK is on the Mac only. Copy it to the
  PC yourself, or allow the Mac runner to pass `atak.apk` and the plugin APK
  to the PC runner as a short-lived private workflow artifact?
