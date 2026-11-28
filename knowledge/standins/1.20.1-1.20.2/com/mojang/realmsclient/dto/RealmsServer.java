package mmt.standin.com.mojang.realmsclient.dto;

import com.mojang.util.UndashedUuid;
import java.util.UUID;
import net.minecraft.Util;

/**
 * 1.20.1's RealmsServer.ownerUUID on 1.20.2.
 *
 * Read from both releases: 1.20.1's RealmsServer.parse kept the owner's id as Realms sent it, a string
 * (JsonUtils.getStringOr("ownerUUID", json, ""), "" where it was missing). 1.20.2 keeps it as a UUID
 * (JsonUtils.getUuidOr("ownerUUID", json, Util.NIL_UUID), parsed with UndashedUuid.fromStringLenient), the nil UUID where
 * it was missing. Realms names players by undashed ids, so reading the field is the UUID written back undashed
 * (UndashedUuid.toString), and "" for the nil one; writing it parses the string the same lenient way.
 *
 * NeoForge's primers begin at 1.20.2 -> 1.20.3, and Fabric's 1.20.2 post (https://fabricmc.net/2023/09/12/1202.html) does
 * not mention Realms; this is from the game's code alone.
 */
public final class RealmsServer {

    private RealmsServer() {}

    public static String ownerUUID(com.mojang.realmsclient.dto.RealmsServer self) {
        UUID owner = self.ownerUUID;
        return owner == null || owner.equals(Util.NIL_UUID) ? "" : UndashedUuid.toString(owner);
    }

    public static void ownerUUID(com.mojang.realmsclient.dto.RealmsServer self, String value) {
        self.ownerUUID = value == null || value.isEmpty() ? Util.NIL_UUID : UndashedUuid.fromStringLenient(value);
    }
}
