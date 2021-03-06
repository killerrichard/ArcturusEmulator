package com.eu.habbo.messages.incoming.rooms.users;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.achievements.AchievementManager;
import com.eu.habbo.habbohotel.rooms.Room;
import com.eu.habbo.habbohotel.rooms.RoomChatMessage;
import com.eu.habbo.habbohotel.rooms.RoomChatMessageBubbles;
import com.eu.habbo.habbohotel.users.Habbo;
import com.eu.habbo.messages.incoming.MessageHandler;
import com.eu.habbo.messages.outgoing.rooms.users.RoomUserWhisperComposer;
import com.eu.habbo.plugin.events.users.UserKickEvent;

public class RoomUserKickEvent extends MessageHandler
{
    @Override
    public void handle() throws Exception
    {
        Room room = this.client.getHabbo().getHabboInfo().getCurrentRoom();

        if(room == null)
            return;

        int userId = this.packet.readInt();

        Habbo target = room.getHabbo(userId);

        if(target == null)
            return;

        if (target.hasPermission("acc_unkickable"))
        {
            this.client.sendResponse(new RoomUserWhisperComposer(new RoomChatMessage(Emulator.getTexts().getValue("commands.error.cmd_kick.unkickable").replace("%username%", target.getHabboInfo().getUsername()), this.client.getHabbo(), this.client.getHabbo(), RoomChatMessageBubbles.ALERT)));
            return;
        }

        if (room.isOwner(target))
        {
            return;
        }

        UserKickEvent event = new UserKickEvent(this.client.getHabbo(), target);
        Emulator.getPluginManager().fireEvent(event);

        if(event.isCancelled())
            return;

        if(room.hasRights(this.client.getHabbo()) || this.client.getHabbo().hasPermission("acc_anyroomowner") || this.client.getHabbo().hasPermission("acc_ambassador") || this.client.getHabbo().hasPermission("acc_modtool_ticket_q"))
        {
            room.kickHabbo(target, true);
            AchievementManager.progressAchievement(this.client.getHabbo(), Emulator.getGameEnvironment().getAchievementManager().getAchievement("SelfModKickSeen"));
        }
    }
}
