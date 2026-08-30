# TotalDebug context

## Runtime source

A physical class directory, archive, nested archive, or Java runtime image that contributes classes to one runtime profile.

## Runtime module

The named owner that groups one or more runtime sources for browsing, searching, and provenance.

## Platform module

Minecraft or the active mod-loader platform. They share one module when Minecraft's owning source is also identified as NeoForge, including when NeoForge loader classes come from another platform source. Otherwise they remain distinct modules.

## Mod module

A runtime module backed by declared NeoForge mod metadata.

## Library module

A runtime dependency without declared mod ownership. It remains indexed and navigable but is secondary in browsing.

## Java runtime module

The classes supplied by the exact Java runtime used by Minecraft.
