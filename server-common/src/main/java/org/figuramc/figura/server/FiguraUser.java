package org.figuramc.figura.server;

import org.figuramc.figura.server.avatars.EHashPair;
import org.figuramc.figura.server.json.FiguraUserStruct;
import org.figuramc.figura.server.packets.CustomFSBPacket;
import org.figuramc.figura.server.packets.Packet;
import org.figuramc.figura.server.utils.*;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class FiguraUser {
    private final UUID player;
    private boolean online;
    private final PingCounter pingCounter = new PingCounter();
    private final BitSet prideBadges;
    private @Nullable Pair<String, EHashPair> equippedAvatar;

    private final HashMap<String, EHashPair> ownedAvatars;

    /**
     * 이번 세션에 마지막으로 디스크에 쓴 JSON. {@code null} 이면 쓴 적 없음.
     *
     * <p>★ 더티 boolean 을 쓰지 않는 이유: {@link #prideBadges()} 가 가변 {@code BitSet} 을
     * 그대로 돌려주고 배지 커맨드가 그 객체를 직접 변형하므로, mutator 기반 더티 표시는
     * <b>원리적으로</b> 배지 변경을 놓친다. 상태를 관찰하면 통지 누락이라는 실패 모드가 없다.
     */
    private transient String lastWritten;

    public FiguraUser(UUID player, BitSet prideBadges, Pair<String, EHashPair> equippedAvatar, HashMap<String, EHashPair> ownedAvatars) {
        this.player = player;
        this.online = false;
        this.prideBadges = prideBadges;
        this.equippedAvatar = equippedAvatar;
        this.ownedAvatars = ownedAvatars;
    }

    public UUID uuid() {
        return player;
    }

    public boolean online() {
        return online;
    }

    public boolean offline() {
        return !online;
    }

    public PingCounter pingCounter() {
        return pingCounter;
    }

    public BitSet prideBadges() {
        return prideBadges;
    }

    public @Nullable Pair<String, EHashPair> equippedAvatar() {
        return equippedAvatar;
    }

    public HashMap<String, EHashPair> ownedAvatars() {
        return ownedAvatars;
    }

    public void sendPacket(Packet packet) {
        FiguraServer.getInstance().sendPacket(player, packet);
    }

    private String serialize() {
        FiguraUserStruct struct = new FiguraUserStruct();
        if (equippedAvatar != null) {
            struct.equippedAvatar = equippedAvatar.left();
            struct.avatarHash = equippedAvatar.right();
        }
        struct.prideBadges = prideBadges;
        struct.ownedAvatars = ownedAvatars;
        return FiguraServer.getInstance().GSON.toJson(struct);
    }

    /**
     * 임시 파일에 쓰고 fsync 한 뒤 rename 한다 — "옛 내용 또는 새 내용, 절대 절단 없음".
     *
     * <p>⚠ 주기 저장에서 이것은 선택이 아니라 <b>전제</b>다. 예전에는 목적지 파일을 직접
     * truncate 하고 썼는데, 그 창에서 서버가 죽으면 잘린 JSON 이 남는다. 잘린 파일은
     * {@code loadPlayerData} 의 레거시 폴백을 타고 <b>빈 유저</b>가 되어 장착 아바타와
     * 배지가 조용히 사라진다. 저장 빈도를 올리면서 이걸 안 고치면
     * 데이터를 지키려는 기능이 데이터 손실 확률을 올린다.
     */
    private static void writeAtomic(Path file, String json) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
            fos.write(json.getBytes(UTF_8));
            fos.getFD().sync();
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 내용이 지난번 쓴 것과 다를 때만 쓴다. 주기 저장 전용.
     *
     * @return 실제로 디스크에 썼으면 true
     */
    public boolean saveIfChanged(Path file) throws IOException {
        String json = serialize();
        if (json.equals(lastWritten)) return false;
        // ⚠⚠ 아무것도 없는 유저에게 **파일을 새로 만들지 않는다.**
        //   FiguraUserManager.userExists() 는 파일 존재만 보므로, 빈 파일이 생기면
        //   C2SFetchUserdataPacketHandler 가 S2CUserdataNotFoundPacket 대신 **빈 userdata** 를 보내고
        //   클라의 공식 클라우드 폴백(UserdataApplier.userdataNotFound → getUserFromBackend)이 끊긴다.
        //   → 공식 클라우드만 쓰는 플레이어의 아바타가 이 서버에서 안 보이게 된다.
        //   ★ getUser() 는 누가 조회하기만 해도 유저를 적재하므로, 가드가 없으면 주기 저장이
        //     '한 번이라도 조회된 모든 플레이어' 에게 빈 파일을 뿌린다.
        //   ⚠ 파일이 이미 있으면 쓴다 — 있던 데이터가 지워진 상태도 기록돼야 한다.
        if (isEmpty() && !Files.exists(file)) return false;
        writeAtomic(file, json);
        lastWritten = json;                 // ★ 쓰기가 성공한 뒤에만. 아니면 실패한 유저가 영영 안 써진다
        return true;
    }

    /** 저장할 내용이 하나도 없는가 (장착 없음 · 소유 없음 · 배지 없음). */
    private boolean isEmpty() {
        return equippedAvatar == null && ownedAvatars.isEmpty() && prideBadges.isEmpty();
    }

    /** 무조건 쓴다. 기존 호출자(퇴장·종료·cleanup)의 계약을 그대로 유지한다. */
    public void save(Path file) {
        try {
            String json = serialize();
            writeAtomic(file, json);
            lastWritten = json;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static FiguraUser load(UUID player, Path file) {
        file.getParent().toFile().mkdirs();
        File playerFile = file.toFile();
        try {
            FileInputStream fis = new FileInputStream(playerFile);
            String str = new String(fis.readAllBytes(), UTF_8);
            fis.close();
            FiguraUserStruct struct = FiguraServer.getInstance().GSON.fromJson(str, FiguraUserStruct.class);
            Pair<String, EHashPair> avatar = struct.equippedAvatar != null ? new Pair<>(struct.equippedAvatar, struct.avatarHash) : null;
            // JSON 에 키가 없으면 null 로 역직렬화된다 — 정규화해 두지 않으면 이후 전부 NPE 다
            FiguraUser user = new FiguraUser(player,
                    struct.prideBadges != null ? struct.prideBadges : new BitSet(),
                    avatar,
                    struct.ownedAvatars != null ? struct.ownedAvatars : new HashMap<>());
            // ★ 방금 읽은 내용이 곧 디스크 내용이다. 이걸 안 찍으면 첫 주기 저장에서 전원이 한 번씩 재작성된다
            user.lastWritten = user.serialize();
            return user;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Deprecated(forRemoval = true)
    public static FiguraUser loadByteBuf(UUID player, Path playerFile) {
        try (FileInputStream fis = new FileInputStream(playerFile.toFile())) {
            InputStreamByteBuf buf = new InputStreamByteBuf(fis);
            return loadByteBuf(player, buf);
        } catch (FileNotFoundException e) {
            return new FiguraUser(player, new BitSet(), null, new HashMap<>());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Deprecated(forRemoval = true)
    public static FiguraUser loadByteBuf(UUID player, IFriendlyByteBuf buf) {
        int length = buf.readVarInt();
        byte[] arr = buf.readBytes(length);
        BitSet prideBadges = BitSet.valueOf(arr);
        int equippedAvatarsCount = buf.readVarInt();
        Pair<String, EHashPair> equippedAvatar = null;
        for (int i = 0; i < equippedAvatarsCount; i++) {
            String id = new String(buf.readByteArray(256), UTF_8);
            Hash hash = buf.readHash();
            Hash ehash = buf.readHash();
            if (equippedAvatar == null) equippedAvatar = new Pair<>(id, new EHashPair(hash, ehash));
        }
        HashMap<String, EHashPair> ownedAvatars = new HashMap<>();
        int ownedAvatarsCount = buf.readVarInt();
        for (int i = 0; i < ownedAvatarsCount; i++) {
            String id = new String(buf.readByteArray(256), UTF_8);
            Hash hash = buf.readHash();
            Hash ehash = buf.readHash();
            ownedAvatars.put(id, new EHashPair(hash, ehash));
        }
        // ⚠ 레거시 포맷에서 온 것은 JSON 으로 아직 안 써졌다 — lastWritten 을 찍지 않아
        //   첫 주기 저장이 새 포맷으로 한 번 기록하게 둔다
        return new FiguraUser(player, prideBadges, equippedAvatar, ownedAvatars);
    }

    public Hash findEHash(Hash hash) {
        var avatar = equippedAvatar();
        if (avatar != null) {
            var pair = avatar.right();
            if (pair.hash().equals(hash)) return pair.ehash();
        }
        for (EHashPair pair: ownedAvatars.values()) {
            if (pair.hash().equals(hash)) return pair.ehash();
        }
        return null;
    }

    public void update() {

    }

    public void setOnline() {
        online = true;
    }

    public void setOffline() {
        online = false;
    }

    public void removeOwnedAvatar(String avatarId) {
        if (ownedAvatars.containsKey(avatarId)) {
            EHashPair avatar = ownedAvatars.remove(avatarId);
            try {
                FiguraServer.getInstance().avatarManager().getAvatarMetadata(avatar.hash()).owners().remove(uuid());
            } catch (RuntimeException re) {
                FiguraServer.getInstance().logError("Failed to remove owned avatar", re);
            }
        }
    }

    public void removeEquippedAvatar() {
        if (equippedAvatar != null) {
            try {
                FiguraServer.getInstance().avatarManager().getAvatarMetadata(equippedAvatar.right().hash()).equipped().remove(uuid());
                equippedAvatar = null;
            } catch (RuntimeException re) {
                FiguraServer.getInstance().logError("Failed to remove equipped avatar", re);
            }
        }
    }

    public void replaceOrAddOwnedAvatar(String avatarId, Hash hash, Hash ehash) {
        try {
            FiguraServer.getInstance().avatarManager().getAvatarMetadata(hash).owners().put(uuid(), ehash);
            ownedAvatars.put(avatarId, new EHashPair(hash, ehash));
        } catch (RuntimeException re) {
            FiguraServer.getInstance().logError("Failed to replace/add avatar", re);
        }
    }

    public void setEquippedAvatar(String avatarId, Hash hash, Hash ehash) {
        try {
            FiguraServer.getInstance().avatarManager().getAvatarMetadata(hash).equipped().put(uuid(), ehash);
            equippedAvatar = new Pair<>(avatarId, new EHashPair(hash, ehash));
        } catch (RuntimeException re) {
            FiguraServer.getInstance().logError("Failed to set equipped avatar", re);
        }
    }

    public int getAvatarsCountWithId(String avatarId) {
        return ownedAvatars().size() + (ownedAvatars().containsKey(avatarId) ? 0 : 1);
    }

    public void sendFSBPacket(String id, byte[] data) {
        sendFSBPacket(uuid(), id, data);
    }

    public void sendFSBPacket(UUID avatarOwner, String id, byte[] data) {
        sendPacket(new CustomFSBPacket(avatarOwner, id.hashCode(), data));
    }

    public static class PingCounter {
        private int bytesSent; // Amount of total bytes sent in last 20 ticks
        private int pingsSent; // Amount of pings sent in last 20 ticks

        public int bytesSent() {
            return bytesSent;
        }

        public int pingsSent() {
            return pingsSent;
        }

        public void addPing(int size) {
            pingsSent++;
            bytesSent += size;
        }

        public void reset() {
            bytesSent = 0;
            pingsSent = 0;
        }
    }
}
