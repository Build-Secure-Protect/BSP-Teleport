package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.teleport.AdminActions;
import com.mrgregles.bsp_teleport.util.ServerText;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * {@code /tpall to} from the console: it runs without a player, and the console reads English, not message keys.
 * The moves themselves need the network and are the owner's check.
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class ConsoleTpAllGameTests {
    @GameTest(template = "empty")
    public static void tpall_to_runs_from_the_console_in_english(GameTestHelper h) {
        String text = ServerText.msg("admin.sent_everyone", "BSP-Spawn", 3).getString();
        h.assertTrue(text.equals("Sending everyone on the network to BSP-Spawn (3 from this server)."), "the console gets the English text: " + text);
        String spot = ServerText.msg("admin.sent_everyone_spot", "BSP-Spawn", 0, "100 70 -200").getString();
        h.assertTrue(spot.equals("Sending everyone on the network to 100 70 -200 on BSP-Spawn (0 from this server)."), "numbered arguments work: " + spot);
        CommandSourceStack console = h.getLevel().getServer().createCommandSourceStack();
        // No network in the test server: refused with a message, never "a player is required".
        h.assertTrue(!AdminActions.sendEveryone(console, "BSP-Spawn", new AdminActions.Vec("minecraft:overworld", 0, 64, 0)),
                "without the network the console is told no and nothing moves");
        h.succeed();
    }
}
