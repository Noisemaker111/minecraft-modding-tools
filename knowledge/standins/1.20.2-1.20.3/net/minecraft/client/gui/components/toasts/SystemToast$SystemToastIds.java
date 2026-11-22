package mmt.standin.net.minecraft.client.gui.components.toasts;

import net.minecraft.client.gui.components.toasts.SystemToast;

/**
 * 1.20.2's SystemToast.SystemToastIds, which 1.20.3 replaced with the class SystemToast.SystemToastId.
 *
 * Read from both releases: 1.20.2's SystemToastIds was an enum naming each kind of system toast (TUTORIAL_HINT,
 * NARRATOR_TOGGLE, WORLD_BACKUP, PACK_LOAD_FAILURE, WORLD_ACCESS_FAILURE, PACK_COPY_FAILURE, PERIODIC_NOTIFICATION, 5000 ms
 * each, UNSECURE_SERVER_WARNING 10000 ms); a toast's id was its token, so addOrUpdate replaced the toast of the same id.
 * 1.20.3's SystemToastId is a class anyone can make (new SystemToastId(), new SystemToastId(displayTime)) with the same
 * constants as public static fields, all but TUTORIAL_HINT. So each old constant stands for 1.20.3's constant of that
 * name, and TUTORIAL_HINT for one id of 5000 ms of its own, shared by every use as the enum constant was.
 *
 * NeoForge's primers begin at 1.20.4 -> 1.20.5, and Fabric's 1.20.3 post (https://fabricmc.net/2023/11/30/1203.html)
 * does not mention system toasts; this is from the game's code alone.
 */
public enum SystemToast$SystemToastIds {
    TUTORIAL_HINT(new SystemToast.SystemToastId()),
    NARRATOR_TOGGLE(SystemToast.SystemToastId.NARRATOR_TOGGLE),
    WORLD_BACKUP(SystemToast.SystemToastId.WORLD_BACKUP),
    PACK_LOAD_FAILURE(SystemToast.SystemToastId.PACK_LOAD_FAILURE),
    WORLD_ACCESS_FAILURE(SystemToast.SystemToastId.WORLD_ACCESS_FAILURE),
    PACK_COPY_FAILURE(SystemToast.SystemToastId.PACK_COPY_FAILURE),
    PERIODIC_NOTIFICATION(SystemToast.SystemToastId.PERIODIC_NOTIFICATION),
    UNSECURE_SERVER_WARNING(SystemToast.SystemToastId.UNSECURE_SERVER_WARNING);

    final SystemToast.SystemToastId id;

    SystemToast$SystemToastIds(SystemToast.SystemToastId id) {
        this.id = id;
    }

    static SystemToast$SystemToastIds of(SystemToast.SystemToastId id) {
        for (SystemToast$SystemToastIds ids : values()) {
            if (ids.id == id) {
                return ids;
            }
        }
        return null;
    }
}
