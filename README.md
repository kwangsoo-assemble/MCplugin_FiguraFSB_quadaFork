# MCplugin_FiguraFSB_quadaFork

**[한국어](#한국어)** 설명은 아래에 있습니다.

> A **Figura Server Backend (FSB)** server plugin for Paper / Purpur **1.21.8**.
> This is an **AI-assisted fork**: the FSB server implementation from the official [Figura](https://github.com/FiguraMC/Figura)
> repository's 1.20.6 branch (the `server-common` and `spigot` modules) is split out into a standalone plugin, ported
> to 1.21.8 and extended. The server you are connected to relays Figura avatar uploads, downloads and pings instead of
> the official cloud.

> ⚠ **This is an unofficial fork** and is not affiliated with FiguraMC. Most changes were written with an AI coding
> assistant (Claude Code) and checked by the maintainer in real use.
> ⚠ The companion client is [MCmod_Figura_quadaFork](https://github.com/kwangsoo-assemble/MCmod_Figura_quadaFork) —
> because the protocol changed, it is **not wire-compatible with the official Figura client.**

## At a glance

| Item | Value |
|---|---|
| Server | Paper / Purpur 1.21.8 (`api-version: 1.21`) |
| Java | 21 |
| Plugin | name `FiguraFSB`, version `0.1.6.2` (see 11), command `/fsb`, data folder `plugins/FiguraFSB/` |
| Companion client | MCmod_Figura_quadaFork (shares `server-common/` as the same source) |
| Companion plugin | [MCplugin_FiguraController](https://github.com/kwangsoo-assemble/MCplugin_FiguraController) — server-owned states and signals (built on this plugin's API; see below) |

## Changes from upstream

Compared against the `server-common` and `spigot` modules of upstream branch `1.20.6` at
[`eff5f43`](https://github.com/FiguraMC/Figura/commit/eff5f43cd3c542bc58641ae2d43d3e8f73d22043).
In the **Code** paths under each item, `server-common/…/` is short for `server-common/src/main/java/org/figuramc/figura/server/`
and `spigot/…/` for `spigot/src/main/java/org/figuramc/figura/server/`. **(new)** marks files that upstream does not have.

### 1. Standalone plugin, ported to 1.21.8

Upstream, these were modules inside the Figura client repository. `server-common` (protocol and logic with no
Minecraft dependency) and `spigot` (the Bukkit plugin) are split out into a separately built project and adapted to
Paper / Purpur 1.21.8. **This repository holds the original of `server-common/`** — the client fork copies it (if the
two differ, communication breaks).

Code: the root build files (`settings.gradle`, `build.gradle`, `gradle.properties`) are written from scratch ·
`server-common/build.gradle` · `spigot/build.gradle` · `spigot/src/main/resources/plugin.yml` (name `FiguraFSB`, `api-version`)

### 2. Server packets for other players' avatars (protocol change)

`CustomFSBPacket` becomes `(avatarOwner UUID, id, data)` — avatars other than the host's can exchange packets with the
server. The packet id is the Java `String.hashCode()` of the packet name. ⚠ This breaks wire compatibility with the
official FSB.

Code: `server-common/…/packets/CustomFSBPacket.java` · `server-common/…/FiguraCustomPackets.java`

### 3. Setting `allowNonHostPackets` (default `false`)

When `false`, the server drops client-to-server packets whose `avatarOwner` differs from the sender. Server-to-client
packets are always delivered, regardless of this value.

Code: `server-common/…/FiguraServerConfig.java` · `server-common/…/packets/handlers/c2s/C2SCustomFSBPacketHandler.java`

### 4. `/fsb reload`

Permission `figura.reload` (operators by default). From code: `FiguraFSBApi.reloadConfig()`. Values sent on join
(ping and avatar limits) apply to players who join after the reload.

Code: `server-common/…/commands/FiguraServerCommands.java` · `server-common/…/FiguraServer.java` · `server-common/…/FiguraPermissionNodes.java`

### 5. Ping rate limit bug fixed

Upstream only sent an error packet for pings over the limit and **did not block them**. They are blocked now.

Code: `server-common/…/packets/handlers/c2s/C2SPingPacketHandler.java`

### 6. Public API for other plugins (`org.figuramc.figura.server.api`)

- `FiguraFSBApi` — `sendPing` / `broadcastPing`, `sendServerPacket(receiver[, avatarOwner], name, data)`,
  `onServerPacket(name, listener)` (one listener per name — registering again replaces it), `isConnected`, `reloadConfig`
- `FSBPingEvent` — a Bukkit event fired when a ping is received (cancellable)
- `PingReceivedEvent` — an internal `server-common` event
- Usage and examples are in `API.md`. For persistent states and signals, see «Companion plugin» below.

Code: `spigot/…/api/FiguraFSBApi.java` · `spigot/…/api/FSBPingEvent.java` · `server-common/…/events/pings/PingReceivedEvent.java` (all new) ·
`spigot/…/FiguraSpigot.java`

### 7. Newer Paper compatibility

On versions where `CraftPlayer.addChannel` and `removeChannel` no longer exist, the plugin falls back to manipulating
the `channels()` set directly.

Code: `spigot/…/SpigotUtils.java`

### 8. Players who are already online survive a plugin reload

Upstream, after the plugin was enabled again (server `/reload`, a plugin manager), it no longer knew the players who
were already online — server-to-client packets for them were silently dropped until they reconnected. Now the plugin
registers online players again when it is enabled and sends them a fresh handshake. Clients that accept it carry on
without reconnecting, and older clients ignore it.

Code: `spigot/…/FiguraSpigot.java`

### 9. Periodic saving of user data

Upstream saved user data (equipped avatars and badges) only on quit and on a clean shutdown, so if the server was
killed, that session's uploads were lost.

- Setting `autosaveIntervalSeconds` (default 300 seconds) — `0` turns it off and behaves like upstream.
- **Atomic saves** — written to a temporary file, fsynced, then swapped in. The result is either the old content or
  the new content, never a truncated file.
- Only users whose content changed are written, and users with nothing to save do not get a file.
- Save errors are **isolated per user** — if one user fails, the others are still saved.
- A user file that cannot be read is moved aside as `*.corrupt`, instead of being read as an empty user and
  overwritten by the next save as before.

Code: `server-common/…/FiguraUser.java` · `server-common/…/FiguraUserManager.java` · `server-common/…/FiguraServerConfig.java`

### 10. `/fsb cleanup` — removing unused avatars and player records

Upstream never deleted avatar files or player records, so they piled up. Permission `figura.cleanup` (operators by default).

- `/fsb cleanup` — **only reports** what would be deleted (changes nothing).
- `/fsb cleanup confirm` — deletes avatars nobody uses, metadata whose avatar file is gone, and empty player records.
- `/fsb cleanup purge` → `purge confirm` — also deletes the record of every player who is not online.
  ⚠ The avatars those players uploaded go with them, and this cannot be undone.
- Who uses an avatar is **recomputed from the user files**, not taken from the avatar metadata.
  Protected avatars, avatars currently loaded in memory, and records of online players are skipped.

Code: `server-common/…/commands/FiguraCleanupCommand.java` · `server-common/…/FiguraCacheCleanup.java` (both new) ·
`server-common/…/avatars/FiguraServerAvatarManager.java`

### 11. Version number

To avoid confusion with the upstream FSB, the version adds a **fourth number** after the upstream version — `0.1.6.2`
(jar: `figura-fsb-0.1.6.2.jar`). The fourth number is this fork's own release number — the first release used the tag `0.1.6-but-ai-edited.1`.
The companion client (MCmod_Figura_quadaFork) follows the same rule, but each repository counts its own releases.

Code: root `gradle.properties`

## Companion plugin — MCplugin_FiguraController

[MCplugin_FiguraController](https://github.com/kwangsoo-assemble/MCplugin_FiguraController) keeps player, mob and global "states" on the server and sends only what changed to avatars. It is built on this
plugin's public API.

- It uses `FiguraFSBApi.onServerPacket("figuracontroller:v1", …)`, `sendServerPacket(receiver, avatarOwner, "figuracontroller:v1", …)` and
  `isConnected(uuid)`. It owns that packet name — other plugins must not register a listener for it (one listener per name; a later one replaces it).
- For **persistent states and signals** between the server and avatars, prefer FiguraController over hand-made server packets: late joiners,
  reloaded avatars and Flashback rewinds all see the right state (the client fork writes the store into every recording snapshot).
- For one-shot messages in your own format, keep using server packets (`server_packets`).

## Settings (`plugins/FiguraFSB/config.json`)

| Key | Default | Meaning |
|---|---|---|
| `pingsRateLimit` | `32` | Pings per second per player |
| `pingsSizeLimit` | `1024` | Ping bytes per second per player |
| `avatarSizeLimit` | `102400` | Avatar size limit (bytes) |
| `avatarCountLimit` | `1` | Avatars per player |
| `allowNonHostPackets` | `false` | Allow packets from avatars other than the host's (**added**) |
| `autosaveIntervalSeconds` | `300` | Periodic save interval, `0` turns it off (**added**) |

## Building

JDK 21 is required.

```
./gradlew build
```

Output: `spigot/build/libs/figura-fsb-<version>.jar` (a jar with `server-common` bundled inside). The
`*-unshaded.jar` is an intermediate file — do not put it on a server.

## License

**PolyForm Noncommercial 1.0.0**, the same as the upstream Figura repository (`LICENSE.md`) — noncommercial use only.

---

# 한국어

> **Figura Server Backend (FSB)** 서버 플러그인 — Paper · Purpur **1.21.8** 용.
> 공식 [Figura](https://github.com/FiguraMC/Figura) 저장소 1.20.6 브랜치에 있던 FSB 서버 구현(`server-common` · `spigot` 모듈)을
> 독립 플러그인으로 떼어 1.21.8 로 옮기고 기능을 더한 **AI 수정 포크**다.
> 접속한 서버가 공식 클라우드 대신 Figura 아바타의 업로드 · 다운로드 · 핑을 중계한다.

> ⚠ **비공식 포크다.** FiguraMC 와 관련이 없다. 수정의 대부분은 AI 코딩 도구(Claude Code)로 작성했고
> 유지보수자가 실제 사용으로 확인했다.
> ⚠ 짝 클라이언트는 [MCmod_Figura_quadaFork](https://github.com/kwangsoo-assemble/MCmod_Figura_quadaFork) 다 —
> 프로토콜을 바꿨기 때문에 **공식 Figura 클라이언트와는 통신 호환되지 않는다.**

## 한눈에

| 항목 | 값 |
|---|---|
| 서버 | Paper · Purpur 1.21.8 (`api-version: 1.21`) |
| Java | 21 |
| 플러그인 | 이름 `FiguraFSB` · 버전 `0.1.6.2` (아래 11) · 명령 `/fsb` · 데이터 폴더 `plugins/FiguraFSB/` |
| 짝 클라이언트 | MCmod_Figura_quadaFork (`server-common/` 을 같은 소스로 공유한다) |
| 함께 쓰는 플러그인 | [MCplugin_FiguraController](https://github.com/kwangsoo-assemble/MCplugin_FiguraController) — 서버가 주인인 상태 · 신호 (이 플러그인의 API 위에 얹혀 있다 · 아래) |

## 원본 대비 변경 사항

비교 기준은 원본 `1.20.6` 브랜치의 [`eff5f43`](https://github.com/FiguraMC/Figura/commit/eff5f43cd3c542bc58641ae2d43d3e8f73d22043) 에 있는
`server-common` · `spigot` 모듈이다. 각 항목의 **코드** 경로에서 `server-common/…/` 는 `server-common/src/main/java/org/figuramc/figura/server/`,
`spigot/…/` 는 `spigot/src/main/java/org/figuramc/figura/server/` 를 줄인 것이다. **(새)** 는 원본에 없던 파일이다.

### 1. 독립 플러그인 + 1.21.8 이식

원본은 Figura 클라이언트 저장소 안의 모듈이었다. `server-common`(마인크래프트 의존이 없는 프로토콜 · 로직)과
`spigot`(Bukkit 플러그인) 을 떼어 따로 빌드하는 프로젝트로 만들고 Paper · Purpur 1.21.8 에 맞췄다.
**`server-common/` 의 원본은 이 저장소**다 — 클라이언트 포크는 이것을 복사해 쓴다(두 쪽이 다르면 통신이 깨진다).

코드: 루트 빌드 파일(`settings.gradle` · `build.gradle` · `gradle.properties`)은 새로 썼다 · `server-common/build.gradle` ·
`spigot/build.gradle` · `spigot/src/main/resources/plugin.yml` (이름 `FiguraFSB`, `api-version`)

### 2. 다른 플레이어 아바타의 서버 패킷 (프로토콜 변경)

`CustomFSBPacket` 을 `(avatarOwner UUID, id, data)` 로 바꿨다 — 호스트가 아닌 아바타도 서버와 패킷을 주고받을 수 있다.
패킷 id 는 패킷 이름의 Java `String.hashCode()` 다. ⚠ 공식 FSB 와 통신 호환이 깨진다.

코드: `server-common/…/packets/CustomFSBPacket.java` · `server-common/…/FiguraCustomPackets.java`

### 3. 설정 `allowNonHostPackets` (기본 `false`)

`false` 면 `avatarOwner` 가 보낸 사람과 다른 클라이언트→서버 패킷을 서버가 버린다. 서버→클라이언트 방향은 이 값과
상관없이 언제나 전달한다.

코드: `server-common/…/FiguraServerConfig.java` · `server-common/…/packets/handlers/c2s/C2SCustomFSBPacketHandler.java`

### 4. `/fsb reload`

권한 `figura.reload`(기본 OP). 코드에서는 `FiguraFSBApi.reloadConfig()`. 접속할 때 넘기는 값(핑 · 아바타 제한)은
리로드한 뒤 접속한 플레이어부터 적용된다.

코드: `server-common/…/commands/FiguraServerCommands.java` · `server-common/…/FiguraServer.java` · `server-common/…/FiguraPermissionNodes.java`

### 5. 핑 속도 제한 버그 수정

원본은 제한을 넘은 핑에 오류 패킷만 보내고 **막지는 않았다.** 이제 실제로 막는다.

코드: `server-common/…/packets/handlers/c2s/C2SPingPacketHandler.java`

### 6. 다른 플러그인용 공개 API (`org.figuramc.figura.server.api`)

- `FiguraFSBApi` — `sendPing` · `broadcastPing`, `sendServerPacket(receiver[, avatarOwner], name, data)`,
  `onServerPacket(name, listener)` (이름당 리스너 하나 — 다시 등록하면 덮어쓴다), `isConnected`, `reloadConfig`
- `FSBPingEvent` — 핑을 받았을 때 나는 Bukkit 이벤트 (취소 가능)
- `PingReceivedEvent` — `server-common` 내부 이벤트
- 사용법 · 예제는 `API.md` 에 있다. 지속 상태 · 신호는 아래 «함께 쓰는 플러그인».

코드: `spigot/…/api/FiguraFSBApi.java` · `spigot/…/api/FSBPingEvent.java` · `server-common/…/events/pings/PingReceivedEvent.java` (모두 새) ·
`spigot/…/FiguraSpigot.java`

### 7. 신버전 Paper 호환

`CraftPlayer.addChannel` · `removeChannel` 이 없어진 버전에서는 `channels()` 집합을 직접 다루도록 폴백한다.

코드: `spigot/…/SpigotUtils.java`

### 8. 플러그인을 다시 불러와도 접속 중인 플레이어를 놓치지 않는다

원본은 플러그인이 다시 켜지면(서버 `/reload` · 플러그인 관리자) 이미 접속해 있던 플레이어를 모르는 상태가 됐다 —
그 플레이어가 다시 접속할 때까지 서버→클라이언트 패킷이 조용히 사라졌다. 이제 켜질 때 접속 중인 플레이어를 다시 등록하고
핸드셰이크를 다시 보낸다. 받아들이는 클라이언트는 다시 접속하지 않아도 이어지고, 옛 클라이언트는 무시한다.

코드: `spigot/…/FiguraSpigot.java`

### 9. 사용자 데이터 주기 저장

원본은 퇴장 · 정상 종료 때만 사용자 데이터(장착 아바타 · 뱃지)를 저장해, 서버가 강제로 꺼지면 그 세션의 업로드가 사라졌다.

- 설정 `autosaveIntervalSeconds` (기본 300초) — `0` 이면 끄고 원본처럼 동작한다.
- **원자적 저장** — 임시 파일에 쓰고 fsync 한 뒤 바꿔 끼운다. 옛 내용 아니면 새 내용이고, 잘린 파일이 남지 않는다.
- 내용이 바뀐 사용자만 쓰고, 아무것도 없는 사용자는 파일을 만들지 않는다.
- 저장 오류를 **사용자 단위로 격리**한다 — 한 명이 실패해도 나머지는 저장된다.
- 읽지 못한 사용자 파일은 `*.corrupt` 로 옮겨 둔다 — 예전처럼 빈 사용자로 읽고 다음 저장에서 원래 파일을 덮어쓰지 않는다.

코드: `server-common/…/FiguraUser.java` · `server-common/…/FiguraUserManager.java` · `server-common/…/FiguraServerConfig.java`

### 10. `/fsb cleanup` — 쓰지 않는 아바타 · 플레이어 기록 정리

원본은 아바타 파일과 플레이어 기록을 지우지 않아 계속 쌓였다. 권한 `figura.cleanup`(기본 OP).

- `/fsb cleanup` — 무엇을 지울지 **보고만** 한다 (아무것도 바꾸지 않는다).
- `/fsb cleanup confirm` — 아무도 쓰지 않는 아바타, 본체가 없는 메타데이터, 빈 플레이어 기록을 지운다.
- `/fsb cleanup purge` → `purge confirm` — 접속해 있지 않은 플레이어의 기록까지 전부 지운다.
  ⚠ 그 플레이어들이 올린 아바타도 함께 사라지고 되돌릴 수 없다.
- 누가 아바타를 쓰는지는 아바타 메타데이터가 아니라 **사용자 파일에서 다시 계산**한다.
  보호 표시된 아바타 · 메모리에 올라와 있는 아바타 · 접속 중인 플레이어의 기록은 건너뛴다.

코드: `server-common/…/commands/FiguraCleanupCommand.java` · `server-common/…/FiguraCacheCleanup.java` (둘 다 새) ·
`server-common/…/avatars/FiguraServerAvatarManager.java`

### 11. 버전 표기

업스트림 FSB 와 헷갈리지 않도록 업스트림 버전 뒤에 **넷째 자리**를 붙인다 — `0.1.6.2`
(jar 는 `figura-fsb-0.1.6.2.jar`). 넷째 자리가 이 포크의 판 번호다 — 첫 판은 꼬리 `0.1.6-but-ai-edited.1` 였다.
짝 클라이언트(MCmod_Figura_quadaFork)와 같은 규칙이지만 판 번호는 저장소마다 따로 센다.

코드: 루트 `gradle.properties`

## 함께 쓰는 플러그인 — MCplugin_FiguraController

[MCplugin_FiguraController](https://github.com/kwangsoo-assemble/MCplugin_FiguraController) 는 플레이어 · 몹 · 전역 «상태» 를 서버가 들고 바뀐 것만 아바타에 보내는 플러그인이다. 이 플러그인의 공개 API 위에 얹혀 있다.

- 쓰는 것: `FiguraFSBApi.onServerPacket("figuracontroller:v1", …)` · `sendServerPacket(receiver, avatarOwner, "figuracontroller:v1", …)` · `isConnected(uuid)`.
  그 패킷 이름을 차지하므로 다른 플러그인은 이 이름에 리스너를 걸지 않는다(이름당 하나 — 나중 것이 덮어쓴다).
- 서버 ↔ 아바타 사이에 **지속 상태와 신호**가 필요하면 서버 패킷을 직접 짜기보다 FiguraController 를 권한다 — 늦게 들어온 사람 · 다시 불러온 아바타 ·
  Flashback 되감기까지 상태가 맞는다(클라이언트 포크가 녹화 스냅샷에 저장소를 넣는다).
- 일회성 · 자기 포맷의 메시지는 지금처럼 서버 패킷(`server_packets`)을 쓴다.

## 설정 (`plugins/FiguraFSB/config.json`)

| 키 | 기본값 | 뜻 |
|---|---|---|
| `pingsRateLimit` | `32` | 플레이어당 초당 핑 개수 |
| `pingsSizeLimit` | `1024` | 플레이어당 초당 핑 바이트 |
| `avatarSizeLimit` | `102400` | 아바타 크기 제한 (바이트) |
| `avatarCountLimit` | `1` | 플레이어당 아바타 개수 |
| `allowNonHostPackets` | `false` | 호스트가 아닌 아바타의 패킷 허용 (**추가**) |
| `autosaveIntervalSeconds` | `300` | 주기 저장 간격, `0` 이면 끔 (**추가**) |

## 빌드

JDK 21 이 필요하다.

```
./gradlew build
```

산출물: `spigot/build/libs/figura-fsb-<버전>.jar` (`server-common` 을 안에 담은 jar). `*-unshaded.jar` 는 중간 산출물이라 서버에 넣지 않는다.

## 라이선스

원본 Figura 저장소와 같은 **PolyForm Noncommercial 1.0.0** 이다 (`LICENSE.md`) — 비상업적 용도로만 쓸 수 있다.
