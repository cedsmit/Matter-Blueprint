# Matter Blueprint

Matter Blueprint is a Minecraft 1.7.10 addon for
[Matter Manipulator](https://github.com/RecursivePineapple/MatterManipulator),
targeted at GregTech: New Horizons.

The project uses the maintained
[GTNH 1.7.10 ExampleMod](https://github.com/GTNewHorizons/ExampleMod1.7.10)
toolchain and treats Matter Manipulator as a required external mod. Matter
Manipulator is not bundled into the resulting jar.

## Requirements

- A JDK supported by the current GTNH development toolchain
- Matter Manipulator `0.1.55-GTNH` or newer
- Matter Manipulator's transitive GTNH dependencies

## Development

On Windows:

```powershell
.\gradlew.bat setupDecompWorkspace
.\gradlew.bat build
```

The built addon jar is written to `build/libs`.
Release builds use the name `matter-blueprint-<version>.jar`; the initial
development version is `matter-blueprint-0.1.0.jar`. Update `modVersion` in
`gradle.properties` when preparing a new release.

For a one-click Windows build and installer, double-click `build.bat`. It can
detect GTNH instances under Prism Launcher and MultiMC, or accept a Minecraft
instance/mods directory manually. A destination can also be supplied directly:

```bat
build.bat "D:\Games\PrismLauncher\instances\GTNH\.minecraft"
```

## Usage

Hold a Matter Manipulator and select coordinates A and B as usual. Open the
main radial menu's **Blueprints** branch and choose **Save Blueprint**. In the
destination world, choose **Load Blueprint**, followed by **Paste Blueprint**.

Loading automatically starts Matter Manipulator's paste-position action. The
next right-click sets coordinate C and locks the preview there. After that, a
normal right-click opens the radial menu again.

On load, the blueprint bounds are also mapped into the Matter Manipulator as
relative coordinates A `(0, 0, 0)` and B `(sizeX-1, sizeY-1, sizeZ-1)`. This
lets its copy-transform editor operate on the actual blueprint dimensions.

After loading, a Matter Manipulator-style block preview follows the block under
the crosshair. Choose **Lock Blueprint** and click a block to lock the preview
at coordinate C. Choose **Unlock** to clear C and return the preview to the
crosshair; the next right-click on a target block locks it again. Rotation and
flip settings from Edit Transform are
then applied to both this fixed preview and the final paste.

Choose **Edit Blueprint** to open Matter Manipulator's transform editor. Its
paste X/Y/Z controls move the fixed preview, while its rotation, flip, and reset
controls update both the preview and the final paste.

A cyan development outline shows the transformed blueprint bounds so the A/B
dimensions, C position, and rotation result can be checked visually.

Copy A/B act as a hard crop within the loaded blueprint. Blocks outside the
cyan box are omitted from both the preview and the final paste. Copy moves A
and B together, while Stack X/Y/Z repeats the cropped blueprint in either
direction using its transformed size as spacing.

The radial menu's **Undo Paste** action manually restores the exact blocks,
metadata, and tile-entity NBT that existed before the most recent paste. It is a
single-use, per-player undo kept in server memory, must be used in the same
dimension, and is cleared by the next paste or a server restart. Nothing is
rolled back automatically.

Blueprint files are stored only on the client as compressed NBT in
`<minecraft-root>/matter-blueprints/*.mbp`. Saving transfers the captured region
from the server to the client in bounded chunks. Pasting uploads the selected
local file temporarily; the server validates and applies it but does not store
it. This lets one personal library work across single-player worlds and
different servers.

Saving and pasting require operator permission because complete tile-entity NBT
can include item inventories. Matter Blueprint must be installed on both the
client and server so they can perform the transfer protocol.

Block registry names, metadata, air blocks, and full tile-entity NBT are saved.
That generic NBT path preserves mod-specific state such as Carpenter's Blocks
and ForgeMultipart data as long as the required mods and blocks are installed
in the destination instance.

## Extension boundary

New blueprint functionality belongs in the `com.matterblueprint` package. Use
Matter Manipulator's public types through the declared Gradle dependency; do
not copy or embed Matter Manipulator source code in this project.
