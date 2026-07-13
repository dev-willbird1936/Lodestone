// SPDX-License-Identifier: MIT
package dev.lodestone.gateway;

import dev.lodestone.runtime.AuthorizationPolicy;
import dev.lodestone.runtime.LodestoneRuntime;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class StdioMain {
    private StdioMain() {
    }

    public static void main(String[] args) throws Exception {
        try (var runtime = new LodestoneRuntime(AuthorizationPolicy.observeOnly());
             var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            var gateway = new McpGateway(runtime);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                var response = gateway.handle(line);
                if (response != null) {
                    System.out.println(response);
                    System.out.flush();
                }
            }
        }
    }
}
