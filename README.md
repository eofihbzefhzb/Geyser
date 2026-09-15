<img src="https://geysermc.org/img/geyser-1760-860.png" alt="Geyser" width="600"/>

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Discord](https://img.shields.io/discord/613163671870242838.svg?color=%237289da&label=discord)](https://discord.gg/geysermc)
[![Crowdin](https://badges.crowdin.net/e/51361b7f8a01644a238d0fe8f3bddc62/localized.svg)](https://translate.geysermc.org/)

Geyser is a bridge between Minecraft: Bedrock Edition and Minecraft: Java Edition, closing the gap from those wanting to play true cross-platform.

Geyser is an [Open Collaboration](https://opencollaboration.dev/) project.

## NetherNet Portal Bridge Fork

This fork adds the **portal bridge**: a NetherNet ingress that lets Bedrock players join from the
Xbox friends list. It pairs with the [Broadcaster fork](https://github.com/eofihbzefhzb/Broadcaster),
which publishes the Xbox session those players see. This fork builds only `Geyser-Velocity.jar`.

The portal bridge is separate from Geyser's built-in NetherNet support (`bedrock.transport` and
`bedrock.signaling`), which lets players join by server address. The portal bridge uses Xbox
signaling with the Broadcaster account's token, so players join through the published Xbox session.

### The three forks

Each README lists what its own fork changes. The setup guide for the whole stack is the
[Broadcaster README](https://github.com/eofihbzefhzb/Broadcaster#setup).

| Fork | Upstream | Role |
|---|---|---|
| [Broadcaster](https://github.com/eofihbzefhzb/Broadcaster) | [MCXboxBroadcast/Broadcaster](https://github.com/MCXboxBroadcast/Broadcaster) | Publishes the Xbox session players see in their friends list |
| **Geyser** (this repo) | [GeyserMC/Geyser](https://github.com/GeyserMC/Geyser) | Runs the portal bridge, which accepts those players' NetherNet connections. Velocity only |
| [NetworkCompatible](https://github.com/eofihbzefhzb/NetworkCompatible) | [Kas-tle/NetworkCompatible](https://github.com/Kas-tle/NetworkCompatible) | The NetherNet transport library the portal bridge is built on |

### What this fork changes

- **Config:** `advanced.bedrock.portal-bridge`, with `enabled`, `debug-logging`,
  `nether-net-network-id`, `xbox-auth-header` and `xbox-auth-header-file`.
- **Portal bridge** (`network/portal`): connects to Xbox signaling with the Minecraft token from
  Broadcaster's `cache.json`, waiting up to 60 seconds for the file. It accepts NetherNet
  connections through NetworkCompatible and hands each one to a normal Geyser session. Bedrock
  encryption is skipped on these connections, since NetherNet already secures the link.
- **Recovery:** a failed start is retried every 10 seconds at first, backing off to once a
  minute. A token change reloads signaling (checked every 2 seconds), and a dropped signaling
  websocket is rebound (checked every 5 seconds). Neither disconnects players already connected.
- **Files for Broadcaster:** the NetherNet ID is kept across restarts in
  `portal-nethernet-identities.json`. `portal-session-status.json` is written every 5 seconds with
  that ID, readiness, MOTD and player counts, and removed on shutdown.
- **Logging:** each NetherNet join is logged with the player's address, and a player who did not
  get in with the step they reached. `debug-logging` adds every stage of a join.
- **Integrated pack:** enabling it no longer makes resource packs mandatory. Players may decline it
  unless `force-resource-packs` is enabled.
- **Build:** depends on the NetworkCompatible fork from JitPack, plus webrtc-java natives.
  `release.yml` builds Velocity only, as numbered releases; upstream's `build.yml` runs only
  when started by hand.

### Minimal configuration

Install `Geyser-Velocity.jar` from the
[latest release](https://github.com/eofihbzefhzb/Geyser/releases/latest) in Velocity's `plugins`
folder, then set in Geyser's `config.yml`:

```yaml
advanced:
  bedrock:
    portal-bridge:
      enabled: true
      xbox-auth-header-file: /absolute/path/to/mcxbox-standalone/cache/cache.json
      nether-net-network-id: ''
      debug-logging: false
```

`xbox-auth-header-file` must be Broadcaster's cache on the same trusted machine; the token is
never logged. Leave `nether-net-network-id` empty: Geyser generates the ID once, keeps it, and
publishes it in `portal-session-status.json`, where Broadcaster reads it. Requirements, the
Broadcaster configuration, session visibility and join diagnostics are in the
[setup guide](https://github.com/eofihbzefhzb/Broadcaster#setup).

## What is Geyser?
Geyser is a proxy, bridging the gap between Minecraft: Bedrock Edition and Minecraft: Java Edition servers.
The ultimate goal of this project is to allow Minecraft: Bedrock Edition users to join Minecraft: Java Edition servers as seamlessly as possible. However, due to the nature of Geyser translating packets over the network of two different games, *do not expect everything to work perfectly!*

Special thanks to the DragonProxy project for being a trailblazer in protocol translation and for all the team members who have joined us here!

## Supported Versions

| Edition | Supported Versions                                                                                                                     |
|---------|----------------------------------------------------------------------------------------------------------------------------------------|
| Bedrock | 26.0, 26.1, 26.2, 26.3, 26.10, 26.20, 26.21, 26.22, 26.23, 26.30, 26.31, 26.32, 26.33, 26.34, 26.40, 26.41, 26.42, 26.43, 26.44, 26.45 |
| Java    | 26.2 (For older versions, [see this guide](https://geysermc.org/wiki/geyser/supported-versions/))                                      |

## Setting Up
Take a look [here](https://geysermc.org/wiki/geyser/setup/) for how to set up Geyser.

## Links:
- Website: https://geysermc.org
- Docs: https://geysermc.org/wiki/geyser/
- Download: https://geysermc.org/download
- Discord: https://discord.gg/geysermc
- Donate: https://opencollective.com/geysermc
- Test Server: `test.geysermc.org` port `25565` for Java and `19132` for Bedrock

## What's Left to be Added/Fixed
- Near-perfect movement (to the point where anticheat on large servers is unlikely to ban you)
- Some Entity Flags

## What can't be fixed
There are a few things Geyser is unable to support due to various differences between Minecraft Bedrock and Java. For a list of these limitations, see the [Current Limitations](https://geysermc.org/wiki/geyser/current-limitations/) page.

## Compiling
1. Clone the repo to your computer
2. Navigate to the Geyser root directory and run `git submodule update --init --recursive`. This command downloads all the needed submodules for Geyser and is a crucial step in this process.
3. Run `gradlew build` and locate to `bootstrap/build` folder.

## Contributing
Any contributions are appreciated. Please feel free to reach out to us on [Discord](https://discord.gg/geysermc) if
you're interested in helping out with Geyser.

## Libraries Used:
- [Adventure Text Library](https://github.com/KyoriPowered/adventure)
- [CloudburstMC Bedrock Protocol Library](https://github.com/CloudburstMC/Protocol)
- [GeyserMC's Java Protocol Library](https://github.com/GeyserMC/MCProtocolLib)
- [TerminalConsoleAppender](https://github.com/Minecrell/TerminalConsoleAppender)
- [Simple Logging Facade for Java (slf4j)](https://github.com/qos-ch/slf4j)
