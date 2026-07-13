// SPDX-License-Identifier: MIT
package dev.lodestone.legacy189;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;

@Mod(modid = LodestoneLegacy189Mod.MOD_ID, name = "Lodestone", version = "${version}", acceptableRemoteVersions = "*")
public final class LodestoneLegacy189Mod {
    public static final String MOD_ID = "lodestone";
    private Legacy189Endpoint endpoint;

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        endpoint = new Legacy189Endpoint(event.getServer());
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
