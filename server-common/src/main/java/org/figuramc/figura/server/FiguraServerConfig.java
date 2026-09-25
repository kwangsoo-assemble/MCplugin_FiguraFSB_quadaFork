package org.figuramc.figura.server;

import com.google.gson.annotations.SerializedName;

import java.util.UUID;

public final class FiguraServerConfig {
    @SerializedName("pingsRateLimit")
    private int pingsRateLimit = 32;
    @SerializedName("pingsSizeLimit")
    private int pingsSizeLimit = 1024;

    @SerializedName("avatarSizeLimit")
    private int avatarSizeLimit = 102400;
    @SerializedName("avatarCountLimit")
    private int avatarsCountLimit = 1;

    @SerializedName("allowNonHostPackets")
    private boolean allowNonHostPackets = false;

    @SerializedName("autosaveIntervalSeconds")
    private int autosaveIntervalSeconds = 300;

    /**
     * 주기 저장 간격(틱). 0 이하면 <b>비활성</b> = 예전 동작(퇴장·종료·cleanup 때만 저장).
     *
     * <p>★ 되돌리는 법이 {@code "autosaveIntervalSeconds": 0} 한 줄이다 —
     * 주기 저장이 문제를 일으키면 재빌드 없이 정확히 기존 경로로 복귀한다.
     * 기존 config.json 에 이 키가 없어도 Gson 이 초기값을 남기므로 하위호환된다.
     */
    public int autosaveIntervalTicks() {
        return autosaveIntervalSeconds <= 0 ? 0 : autosaveIntervalSeconds * 20;
    }

    /**
     * When true, avatar scripts of OTHER players loaded on a client may also exchange
     * server packets (the packet's avatarOwner may differ from the sending connection).
     * When false (default), only the host's own avatar packets are accepted.
     */
    public boolean allowNonHostPackets() {
        return allowNonHostPackets;
    }

    public int pingsRateLimit(FiguraServer server, UUID player) {
        return Integer.parseInt(server.getOption(player, FiguraPermissionNodes.FIGURA_PINGS_RATELIMIT).orElse(pingsRateLimit + ""));
    }

    public int pingsSizeLimit(FiguraServer server, UUID player) {
        return Integer.parseInt(server.getOption(player, FiguraPermissionNodes.FIGURA_PINGS_SIZELIMIT).orElse(pingsSizeLimit + ""));
    }

    public int avatarSizeLimit(FiguraServer server, UUID player) {
        return Integer.parseInt(server.getOption(player, FiguraPermissionNodes.FIGURA_AVATARS_SIZELIMIT).orElse(avatarSizeLimit + ""));
    }

    public int avatarsCountLimit(FiguraServer server, UUID player) {
        return Integer.parseInt(server.getOption(player, FiguraPermissionNodes.FIGURA_AVATARS_COUNTLIMIT).orElse(avatarsCountLimit + ""));
    }
}
