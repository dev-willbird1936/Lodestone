// SPDX-License-Identifier: MIT
package dev.lodestone.legacy1710;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

@Mod(modid = LodestoneLegacy1710Mod.MOD_ID, name = "Lodestone", version = "${version}", acceptableRemoteVersions = "*")
public final class LodestoneLegacy1710Mod {
    public static final String MOD_ID = "lodestone";
    private Legacy1710Endpoint endpoint;

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        endpoint = new Legacy1710Endpoint(event.getServer());
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
