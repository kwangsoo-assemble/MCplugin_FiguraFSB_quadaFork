# FiguraFSB 플러그인 — 타 플러그인 연동 가이드

FiguraFSB는 Figura 클라이언트의 아바타 업로드/다운로드/핑 통신을 피구라 공식 클라우드 대신
**접속 중인 서버가 직접 중계**하게 하는 Spigot/Paper 플러그인입니다 (MC 1.21.8, Java 21).

이 문서는 다른 플러그인에서 FiguraFSB의 통신 기능을 활용하는 방법을 설명합니다.

---

## 1. 설정

1. 서버 `plugins/` 에 `figura-fsb-0.1.6-spigot.jar` 를 넣습니다. (데이터 폴더: `plugins/FiguraFSB/`)
2. 내 플러그인의 `plugin.yml` 에 의존성을 선언합니다:

```yaml
depend: [FiguraFSB]     # 필수 의존
# 또는
softdepend: [FiguraFSB] # 선택 의존
```

3. 빌드 시 `figura-fsb-0.1.6-spigot.jar` 를 `compileOnly` 로 클래스패스에 추가합니다.

```groovy
dependencies {
    compileOnly files("libs/figura-fsb-0.1.6-spigot.jar")
}
```

진입점은 두 가지입니다:

| 클래스 | 용도 |
|--------|------|
| `org.figuramc.figura.server.api.FiguraFSBApi` | 정적 메서드 API (핑 송신, 서버 패킷 송수신) |
| `org.figuramc.figura.server.api.FSBPingEvent` | Bukkit 이벤트 (클라이언트 핑 감지/차단) |

---

## 2. 클라이언트가 보낸 핑 감지 — `FSBPingEvent`

플레이어의 아바타 스크립트가 `pings:...()` 를 호출하면 서버에 핑이 도착하고,
브로드캐스트 직전에 `FSBPingEvent` 가 **메인 스레드에서** 발생합니다.

```java
@EventHandler
public void onFiguraPing(FSBPingEvent event) {
    UUID sender = event.getSenderUuid(); // 핑을 보낸 플레이어
    int pingId   = event.getPingId();    // 아바타의 핑 함수 id (아래 참고)
    byte[] data  = event.getData();      // Figura 직렬화 포맷의 원시 페이로드
    boolean sync = event.isSync();       // 본인 클라이언트에서도 실행되는 핑인지

    // 특정 조건에서 핑 전파를 막고 싶다면:
    event.setCancelled(true); // 다른 플레이어에게 브로드캐스트되지 않음
}
```

- 이벤트는 FSB 자체 레이트리밋(횟수/용량) 검사를 **통과한 핑에만** 발생합니다.
- 취소하면 다른 플레이어에게 전파되지 않습니다 (보낸 클라이언트에는 별도 통지 없음).
- `getPingId()` 는 Figura가 아바타별 핑 함수에 부여하는 내부 번호입니다.
  같은 아바타에서는 항상 같은 값이므로 "이 아바타의 이 핑" 을 식별하는 키로 쓸 수 있지만,
  아바타를 수정하면 바뀔 수 있습니다. `getData()` 의 내용 해석(Lua 값 역직렬화)은
  Figura 내부 포맷이므로 권장하지 않습니다 — 감지/차단/중계 용도로 쓰세요.

## 3. 서버에서 핑 보내기 — `FiguraFSBApi`

```java
// 특정 플레이어에게만: receiver의 클라이언트에서 avatarOwner 아바타의 핑 함수가 실행됨
FiguraFSBApi.sendPing(avatarOwnerUuid, pingId, data, receiverPlayer);

// FSB 접속자 전원에게 (avatarOwner 본인 포함)
FiguraFSBApi.broadcastPing(avatarOwnerUuid, pingId, data);
```

용도: 핑 릴레이 제어(예: 관전자에게만 전달), 녹화한 핑 재생 등.
`pingId`/`data` 는 Figura 내부 규약을 따라야 하므로, 보통은 `FSBPingEvent` 에서
캡쳐한 값을 그대로 재사용하는 방식(중계)을 권장합니다.
**플러그인 → 아바타로 임의 데이터를 보내려면 아래 4절의 서버 패킷을 쓰는 것이 정석입니다.**

## 4. 플러그인 ↔ 아바타 스크립트 양방향 통신 — 서버 패킷 (권장)

핑과 별개로, FSB에는 서버(플러그인)와 **플레이어 본인의 아바타 스크립트** 간
이름 기반 커스텀 패킷 채널이 있습니다. 서버 연동 콘텐츠에 적합합니다 — **지속 상태 · 신호라면 6절(FiguraController)** 을 먼저 봅니다.

### 서버 → 아바타

```java
// 플러그인 (byte[] 자유 포맷, 최대 32,746 바이트)
FiguraFSBApi.sendServerPacket(player, "myplugin:hello", data);
```

```lua
-- 해당 플레이어의 아바타 스크립트 (호스트에서만 동작)
server_packets["myplugin:hello"] = function(buffer)
    -- buffer: FiguraBuffer, 서버가 보낸 byte[] 그대로
end
```

### 아바타 → 서버

```lua
-- 아바타 스크립트
local buf = data:createBuffer()
buf:writeByteArray("hello")
buf:setPosition(0)
server_packets:sendPacket("myplugin:hello", buf)
```

```java
// 플러그인
FiguraFSBApi.onServerPacket("myplugin:hello", (name, senderUuid, sender, avatarOwner, data) -> {
    // 메인 스레드에서 호출됨. sender는 오프라인이면 null
    // avatarOwner = 이 패킷을 보낸 "아바타 스크립트의 주인"
    //   (기본값에서는 항상 senderUuid와 동일)
});
```

주의사항:

- 패킷 이름은 **전 플러그인 공용 네임스페이스**이며 이름당 리스너 1개만 등록됩니다
  (재등록 시 덮어씀). 반드시 `"내플러그인:패킷명"` 형태로 접두어를 붙이세요.

### 비호스트 아바타 통신 (allowNonHostPackets)

기본값에서 서버 패킷은 "그 플레이어 본인의 장착 아바타(호스트)" 와만 통신합니다.
`config.json` 에서 아래를 켜면 **클라이언트에 로드된 타 플레이어의 아바타 스크립트**도
`server_packets` 송수신이 가능해집니다:

```json
"allowNonHostPackets": true
```

- 예: A의 클라이언트에 로드된 B의 아바타 스크립트가 `server_packets:sendPacket` 호출
  → 서버 리스너에 `senderUuid = A`, `avatarOwner = B` 로 도착.
- 서버→특정 아바타 전송: `FiguraFSBApi.sendServerPacket(receiverPlayer, avatarOwnerUuid, name, data)`
  → receiver의 클라이언트에서 avatarOwner 아바타의 리스너가 실행됨 (보는 사람별 개별 연출 가능).
- 이 스위치가 꺼져 있으면(기본) 서버는 `avatarOwner != sender` 인 C2S 패킷을 조용히 버립니다.
- 신뢰 모델 주의: 악의적 클라이언트는 avatarOwner를 위조할 수 있으므로,
  서버가 아바타를 지정·검수하는 폐쇄 환경에서만 켜는 것을 권장합니다.

## 5. 기타

```java
// 플레이어가 FSB 지원 Figura 클라이언트로 접속했는지 (핸드셰이크 완료 여부)
boolean ok = FiguraFSBApi.isConnected(player.getUniqueId());
```

- 모든 API는 메인 스레드 호출 기준이며, FSB 미초기화/미접속 대상에 대해서는 조용히 무시됩니다.
- 서버 콘솔 디버그 로그: JVM 인자 `-DfiguraDebug=true`
- 인게임 관리 명령: `/fsb` (권한 노드는 `org.figuramc.figura.server.FiguraPermissionNodes` 참고)
- **컨픽 리로드**: 인게임 `/fsb reload` (권한 노드 `figura.reload`, 기본 OP) 또는
  코드에서 `FiguraFSBApi.reloadConfig()`. `plugins/FiguraFSB/config.json` 을 다시 읽습니다.
  단, 핑/아바타 제한처럼 핸드셰이크로 전달되는 값은 **리로드 이후 접속한 플레이어부터** 적용되고,
  `allowNonHostPackets` 는 즉시 적용됩니다.

## 6. 지속 상태 · 신호 · 리플레이 — FiguraController 를 쓴다

서버 ↔ 아바타 사이에 «지속 상태»(지금 모습 · 모드)와 «신호»(지금 이것을 해라)가 필요하면 서버 패킷을 직접 짜기보다
[MCplugin_FiguraController](https://github.com/kwangsoo-assemble/MCplugin_FiguraController) 를 씁니다 — 이 플러그인의 서버 패킷(`figuracontroller:v1`) 위에 얹혀 있습니다.

- 상태는 서버가 들고, 바뀐 것만 그 대상을 보는 클라이언트에 보냅니다. 클라이언트 포크(MCmod_Figura_quadaFork)가 아바타 밖 저장소에 두고
  Lua `server_data` 로 내줍니다.
- 늦게 들어온 사람 · 다시 불러온 아바타 · 다시 생긴 몹 아바타도 지금 값을 받습니다.
- **Flashback** — 클라이언트 포크가 녹화 스냅샷마다 저장소 전체를 넣어, 되감기 · 앞질러 가기도 그 시점 상태가 됩니다.
  서버가 주기적으로 다시 보낼 필요가 없습니다.
- 신호는 저장하지 않습니다 — 되감기 구간에서 다시 돌 수 있으니 연출은 짧게 둡니다.
- 사용법: 그 저장소의 `API.md` (Java API · `/fc` · Lua · 저장 · 설정).

서버 패킷을 직접 쓸 때 알아 둘 것 (Flashback):

- 녹화는 클라이언트가 **받은**(S2C) 패킷만 남깁니다 — 내 아바타가 보낸 것은 서버가 나에게 되돌려 보내야 내 리플레이에 남습니다.
- 순차 재생은 실제 플레이와 같습니다. 되감기는 녹화 스냅샷부터 다시 재생하므로, 그보다 앞에서 보낸 패킷의 효과는 사라집니다.

## 7. 내부 구조 참고 (고급)

셰이딩된 `org.figuramc.figura.server.*` 클래스에 직접 접근할 수도 있습니다:

- `FiguraServer.getInstance()` — 서버 싱글턴. `userManager()`, `customPackets()`, `sendPacket(uuid, packet)`
- `Events.registerHandler(PingReceivedEvent.class, handler)` — Bukkit 이벤트 대신 FSB 내부 이벤트 버스 직접 구독
- 채널 프로토콜: 플러그인 메시지 채널 `figura:*` (핑 `figura:c2s/ping` / `figura:s2c/ping`,
  서버 패킷 `figura:ping/server`, 아바타 전송 `figura:avatars/*`)

단, 내부 클래스는 FSB 버전에 따라 바뀔 수 있으므로 가능하면 `FiguraFSBApi` 와
`FSBPingEvent` 만 사용하는 것을 권장합니다.
