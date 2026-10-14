package com.rpgworldgen.command;

import org.bukkit.command.CommandSender;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PermissionManagerTest {

    private final PermissionManager pm = new PermissionManager();

    private CommandSender senderWith(String... perms) {
        CommandSender s = mock(CommandSender.class);
        for (String p : perms) {
            when(s.hasPermission(p)).thenReturn(true);
        }
        return s;
    }

    @Test
    public void adminPassesEverything() {
        CommandSender admin = senderWith(PermissionManager.ADMIN);
        assertTrue(pm.can(admin, PermissionManager.CREATE));
        assertTrue(pm.can(admin, PermissionManager.GENERATE));
        assertTrue(pm.can(admin, PermissionManager.REGENERATE));
        assertTrue(pm.can(admin, PermissionManager.RELOAD));
        assertTrue(pm.can(admin, PermissionManager.INFO));
    }

    @Test
    public void granularNodesPassOnlyTheirCommand() {
        CommandSender creator = senderWith(PermissionManager.CREATE);
        assertTrue(pm.can(creator, PermissionManager.CREATE));
        assertFalse(pm.can(creator, PermissionManager.GENERATE));
        assertFalse(pm.can(creator, PermissionManager.RELOAD));
    }

    @Test
    public void nobodyHasNoAccess() {
        CommandSender none = mock(CommandSender.class);
        assertFalse(pm.can(none, PermissionManager.INFO));
        assertFalse(pm.can(none, PermissionManager.CREATE));
    }
}
