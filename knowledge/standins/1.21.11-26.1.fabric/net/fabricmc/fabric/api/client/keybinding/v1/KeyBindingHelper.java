package mmt.standin.net.fabricmc.fabric.api.client.keybinding.v1;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/**
 * Fabric API's KeyBindingHelper (for 1.21.11), which Fabric API for 26.1 renamed KeyMappingHelper.
 *
 * Read from both releases' Fabric API (fabric-key-binding-api-v1 for 1.21.11, fabric-key-mapping-api-v1 for 26.1):
 * KeyBindingHelper.registerKeyBinding(KeyMapping) checked the mapping was not null and handed it to
 * KeyBindingRegistryImpl.registerKeyBinding, and getBoundKeyOf(KeyMapping) read the key it is bound to through the
 * module's KeyMappingAccessor. KeyMappingHelper.registerKeyMapping and KeyMappingHelper.getBoundKeyOf do the same through
 * KeyMappingRegistryImpl and the renamed module's accessor. So each old method is the renamed one.
 *
 * Agrees with Fabric's 26.1 post (https://fabricmc.net/2026/03/14/261.html): "API names have been updated to match the official names where applicable", and its migration map (https://docs.fabricmc.net/develop/porting/26.1/fabric-api): "net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper" -> "net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper".
 */
public final class KeyBindingHelper {

    private KeyBindingHelper() {}

    public static KeyMapping registerKeyBinding(KeyMapping mapping) {
        return KeyMappingHelper.registerKeyMapping(mapping);
    }

    public static InputConstants.Key getBoundKeyOf(KeyMapping mapping) {
        return KeyMappingHelper.getBoundKeyOf(mapping);
    }
}
