// SPDX-License-Identifier: MIT
package dev.lodestone.legacy;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;

@Mod(modid = LodestoneLegacyMod.MOD_ID, name = "Lodestone", version = "${version}", acceptableRemoteVersions = "*")
public final class LodestoneLegacyMod {
    public static final String MOD_ID = "lodestone";
    private LegacyBridgeEndpoint endpoint;

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        endpoint = new LegacyBridgeEndpoint(event.getServer());
        endpoint.start();
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        if (endpoint != null) {
            endpoint.close();
            endpoint = null;
        }
    }
}
