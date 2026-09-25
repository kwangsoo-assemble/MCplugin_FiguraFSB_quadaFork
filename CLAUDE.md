# FiguraFSB — Figura Server Backend 플러그인 (Paper/Purpur 1.21.8)

Figura 클라이언트의 아바타 업로드/다운로드/핑을 공식 클라우드 대신 **접속 서버가 중계**하는
Spigot/Paper 플러그인. 공식 FSB 포크(Figura 1.20.6 브랜치)의 server-common+spigot을 분리해
1.21.8로 포팅하고 자체 확장을 얹었다. 짝꿍 클라이언트: `../figura_core_internal`.

## 빌드

- `JAVA_HOME="C:\Program Files\Java\jdk-21"` 지정 후 `./gradlew build` (Gradle 8.8 래퍼)
- 산출물: `spigot/build/libs/figura-fsb-<버전>.jar` (server-common 셰이딩 포함).
  `spigot-*-unshaded.jar`는 중간 산출물 — 서버에 넣지 말 것
- plugin.yml: name=FiguraFSB (데이터 폴더 `plugins/FiguraFSB/`), 명령 `/fsb`

## ★ 공개판 · 내부판 (2026-09-25)

이 폴더는 **내부판**이다 — 작업은 여기서 한다(한글 주석 · 이 CLAUDE.md · 빌드 산출물). 로컬 git 만 있고 **원격이 없다.**
공개판 `../figura_fsbplugin_but_ai_edited` (GitHub `kwangsoo-assemble/figura_fsbplugin_but_ai_edited`) 는 **같은 코드에 주석만 영어**인 사본이다
(이 CLAUDE.md 는 없다 · 원본 README 는 `README.upstream.md` · 영어 README 는 하네스의 한글판을 옮긴 것).

- 코드를 고쳤으면 **공개판에도 같은 변경을 옮긴다** — 주석은 영어로, 내부 이름(하네스 · 콘텐츠명 · 사람 인용)은 빼고 일반 표현으로.
- 판정: `python ../../../mc_content_production/projects/mc_repositories/tools/repos.py status` — «주석을 빼면 같은가 · 공개판 한글 0 · 내부 문서 유출 0 · README 동기».
- 올리기: `python ../../../mc_content_production/projects/mc_repositories/tools/repos.py publish fsbplugin -F <메시지 파일>` — 위가 초록일 때만 커밋 · 푸시한다
  (공개판 커밋 · 푸시는 세션이 자동으로 한다 — 하네스 `CLAUDE.md` 「외부 저장소 — mc_repositories」).
- README 는 **한글판이 원본**이다 — 하네스 `projects/mc_repositories/readme_ko/figura_fsbplugin_but_ai_edited.md`. 기능을 바꾸면 거기 먼저 적고
  영어 README 에 옮긴 뒤 `repos.py mark-readme fsbplugin`.
- 내부판 변경은 **로컬 커밋**으로 남긴다(원격 없음).
- `API.md` 는 공개판에서 **영어로 번역한 본**이다 — 내용을 바꾸면 양쪽을 같이 고친다(대조 도구는 이 파일을 비교하지 않는다).
- `server-common/` 을 고쳤으면 코어 내부판 · 코어 공개판으로 복사까지 해야 끝이다.

## 모듈

- `server-common/` — MC 의존 0(brigadier+gson만)인 프로토콜/로직 본체.
  **이 프로젝트가 원본(source of truth)** — 수정 후 클라 프로젝트
  `../figura_core_internal/server-common/src`로 통째 복사 동기화 필수 (공개판도 같다: `figura_fsbplugin_but_ai_edited` → `figura_core_but_ai_edited`) (불일치 = 와이어 파손)
- `spigot/` — Bukkit 플러그인(플러그인 메시지 채널 `figura:*`), 공개 API는 `api/` 패키지

## 원본 대비 자체 변경 (프로토콜/기능)

1. **CustomFSBPacket 확장**: `(avatarOwner UUID, id, data)` — 비호스트 아바타 통신 지원.
   공식 FSB 클라와 와이어 비호환. id = 패킷 이름의 Java String.hashCode()
2. **컨픽**: `allowNonHostPackets`(기본 false) — false면 avatarOwner≠sender인 C2S를 서버가 버림.
   S2C 방향은 스위치와 무관하게 항상 라우팅
3. **`/fsb reload`** (권한 figura.reload, 기본 OP) + `FiguraFSBApi.reloadConfig()`.
   핸드셰이크 전달 값(핑/아바타 제한)은 리로드 후 접속자부터 적용
4. **핑 레이트리밋 return 버그 수정** (원본은 에러 패킷만 보내고 차단 안 했음)
5. **타 플러그인 공개 API** (`org.figuramc.figura.server.api`, 원본에 없음):
   - `FiguraFSBApi`: sendPing/broadcastPing, sendServerPacket(receiver[,avatarOwner],name,data),
     onServerPacket(name,listener)(이름당 1개, 덮어씀), isConnected, reloadConfig
   - `FSBPingEvent`(Bukkit 이벤트, 취소 가능) — 핑 수신 감지
   - server-common `PingReceivedEvent`(내부 이벤트 버스)
6. **Spigot 신버전 호환**: `CraftPlayer.addChannel/removeChannel` 제거 대응 —
   `SpigotUtils`가 구메서드 → 신 `channels()` Set 직접 조작 순으로 폴백

## 컨픽 수치 의미 (플레이어당·초당)

`pingsRateLimit: 32` = 초당 32회, `pingsSizeLimit: 1024` = 초당 1KB (카운터 20틱 리셋).
핑 1개 하드캡 ~32,745B(플러그인 메시지 한계). 서버 패킷(`figura:ping/server`)엔 레이트리밋 없음
(패킷당 32,762B).

## CH(CommandHelper) 연동

CHFeatures 확장(`mc_repositories/plugins/CHFeatures`)이 이 플러그인 API를 MethodScript 함수로 노출:
`fsb_send_packet(receiver,[avatarOwner,]name,data)`, `fsb_register_packet(name)`,
`fsb_is_connected(player)`, 이벤트 `fsb_server_packet`. CHFeatures 빌드 시 이 플러그인 jar를
libs/에 복사해 install-file 해야 함 (provided 의존).

## 관련 문서

- **타 개발자/플러그인 연동 가이드**: `API.md` (핑·서버패킷 사용법, 리플레이 state/signal 설계 포함)
- 서버 CH↔아바타 브리지 규약: mc_content_production 하네스 `knowledge/shared/fsb_bridge_system.md`
