package org.figuramc.figura.server;

import org.figuramc.figura.server.events.Events;
import org.figuramc.figura.server.events.users.LoadPlayerDataEvent;
import org.figuramc.figura.server.events.users.SavePlayerDataEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.BitSet;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class FiguraUserManager {
    private final FiguraServer parent;
    private final HashMap<UUID, FiguraUser> users = new HashMap<>();
    private int pingsTickCounter = 0;
    private int autosaveTickCounter = 0;

    public FiguraUserManager(FiguraServer parent) {
        this.parent = parent;
    }

    public FiguraUser getUserOrNull(UUID playerUUID) {
        return users.get(playerUUID);
    }

    public boolean userExists(UUID player) {
        return users.containsKey(player) ||
                parent.getUserdataFile(player).toFile().exists() ||
                parent.getOldUserdataFile(player).toFile().exists();
    }

    public FiguraUser getUser(UUID player) {
        return users.computeIfAbsent(player, (p) -> loadPlayerData(player));
    }

    public FiguraUser setupOnlinePlayer(UUID uuid) {
        FiguraUser user = getUser(uuid);
        user.setOnline();
        user.update();
        return user;
    }


    private FiguraUser loadPlayerData(UUID player) {
        LoadPlayerDataEvent playerDataEvent = Events.call(new LoadPlayerDataEvent(player));
        if (playerDataEvent.returned()) return playerDataEvent.returnValue();
        Path dataFile = parent.getUserdataFile(player);
        if (dataFile.toFile().exists()) {
            try {
                return FiguraUser.load(player, dataFile);
            }
            catch (Exception e) {
                // ⚠ 예전에는 이 실패가 레거시 폴백으로 넘어가 조용히 **빈 유저**가 됐고,
                //   다음 저장이 손상본을 정상본으로 덮어써 복구가 불가능해졌다.
                //   주기 저장은 그 "다음 저장" 을 자동으로 앞당기므로 반드시 격리해야 한다.
                parent.logError("Corrupt userdata for " + player + "; quarantining to .corrupt", e);
                try {
                    Files.move(dataFile, dataFile.resolveSibling(dataFile.getFileName() + ".corrupt"),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {}
                return new FiguraUser(player, new BitSet(), null, new HashMap<>());
            }
        }
        return FiguraUser.loadByteBuf(player, parent.getOldUserdataFile(player));
    }

    /**
     * 유저 하나를 저장한다. {@link SavePlayerDataEvent} 를 존중하고 오류를 <b>유저 단위로</b> 격리한다.
     *
     * <p>⚠ 격리가 필수인 이유: {@code FiguraUser.save} 는 IOException 을 RuntimeException 으로
     * 다시 던진다. 격리가 없으면 첫 실패 유저가 <b>나머지 전원의 저장을 중단</b>시키고,
     * {@code close()} 는 그 경우 {@code users.clear()} 에 도달조차 못 한다.
     * 주기 저장은 이 취약점을 상시 스케줄에 얹는 것이므로 먼저 막아야 한다.
     *
     * @param onlyIfChanged true 면 내용이 바뀐 유저만 쓴다 (주기 저장용)
     */
    private void saveUser(FiguraUser user, boolean onlyIfChanged) {
        try {
            if (Events.call(new SavePlayerDataEvent(user)).isCancelled()) return;
            if (onlyIfChanged) user.saveIfChanged(parent.getUserdataFile(user.uuid()));
            else user.save(parent.getUserdataFile(user.uuid()));
        } catch (Exception e) {
            parent.logError("Failed to save userdata for " + user.uuid(), e);
        }
    }

    /** 주기 저장 전용 — 내용이 바뀐 유저만 쓴다. */
    public void saveChanged() {
        for (var user: users.values()) saveUser(user, true);
    }

    public void forEachUser(Consumer<FiguraUser> func) {
        users.forEach((id, user) -> {
            if (user.online()) {
                func.accept(user);
            }
        });
    }

    public void onUserLeave(UUID player) {
        users.computeIfPresent(player, (uuid, pl) -> {
            saveUser(pl, false);
            pl.setOffline();
            return pl;
        });
    }

    /**
     * Writes every loaded user to disk without dropping them from memory.
     * <p>
     * Cache cleanup reads ownership straight off the user files, so anything still only held
     * in memory has to be flushed first or it would look unreferenced and get collected.
     */
    public void saveAll() {
        // ⚠ 여기에 더티/온라인 필터를 넣지 마라. cleanup 이 소유권을 유저 파일에서 재계산하므로
        //   스캔 전 **전원 플러시**가 필요하다. 주기 저장은 별도 saveChanged() 를 쓴다.
        for (var user: users.values()) saveUser(user, false);
    }

    /** UUIDs of users currently held in memory (online or not yet evicted). */
    public java.util.Set<UUID> loadedUsers() {
        return new java.util.HashSet<>(users.keySet());
    }

    public void close() {
        for (var user: users.values()) saveUser(user, false);
        users.clear();
    }

    public void tick() {
        if (pingsTickCounter == 20) {
            forEachUser(user -> user.pingCounter().reset());
            pingsTickCounter = 0;
        }
        pingsTickCounter++;

        // ★ 주기 저장. 예전에는 장착 상태가 **퇴장·정상종료 때만** 디스크로 갔다 —
        //   서버를 kill 로 죽이면 그 세션의 업로드가 통째로 날아가고,
        //   재시작 후 타 클라에는 옛 해시가 광고돼 레거시 아바타가 렌더된다.
        // ⚠ 동기·메인 스레드다. 이 플러그인의 틱은 runTaskTimer(비동기 아님)라
        //   mutator·커맨드·패킷 처리와 같은 스레드이므로 동기화가 필요 없다.
        int interval = parent.config().autosaveIntervalTicks();
        if (interval > 0 && ++autosaveTickCounter >= interval) {
            autosaveTickCounter = 0;
            saveChanged();
        }
    }

    private record FutureHandle(UUID user, CompletableFuture<FiguraUser> future) {}
}
