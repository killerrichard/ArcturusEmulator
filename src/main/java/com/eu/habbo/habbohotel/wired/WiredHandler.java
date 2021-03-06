package com.eu.habbo.habbohotel.wired;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.catalog.CatalogItem;
import com.eu.habbo.habbohotel.items.Item;
import com.eu.habbo.habbohotel.items.interactions.InteractionWiredCondition;
import com.eu.habbo.habbohotel.items.interactions.InteractionWiredEffect;
import com.eu.habbo.habbohotel.items.interactions.InteractionWiredExtra;
import com.eu.habbo.habbohotel.items.interactions.InteractionWiredTrigger;
import com.eu.habbo.habbohotel.items.interactions.wired.WiredTriggerReset;
import com.eu.habbo.habbohotel.items.interactions.wired.effects.WiredEffectGiveReward;
import com.eu.habbo.habbohotel.items.interactions.wired.extra.WiredExtraRandom;
import com.eu.habbo.habbohotel.items.interactions.wired.extra.WiredExtraUnseen;
import com.eu.habbo.habbohotel.rooms.Room;
import com.eu.habbo.habbohotel.rooms.RoomTile;
import com.eu.habbo.habbohotel.rooms.RoomUnit;
import com.eu.habbo.habbohotel.users.Habbo;
import com.eu.habbo.habbohotel.users.HabboBadge;
import com.eu.habbo.habbohotel.users.HabboItem;
import com.eu.habbo.messages.outgoing.catalog.PurchaseOKComposer;
import com.eu.habbo.messages.outgoing.generic.alerts.BubbleAlertComposer;
import com.eu.habbo.messages.outgoing.generic.alerts.BubbleAlertKeys;
import com.eu.habbo.messages.outgoing.generic.alerts.WiredRewardAlertComposer;
import com.eu.habbo.messages.outgoing.inventory.AddHabboItemComposer;
import com.eu.habbo.messages.outgoing.inventory.InventoryRefreshComposer;
import com.eu.habbo.messages.outgoing.rooms.ForwardToRoomComposer;
import com.eu.habbo.messages.outgoing.users.AddUserBadgeComposer;
import com.eu.habbo.plugin.events.furniture.wired.WiredConditionFailedEvent;
import com.eu.habbo.plugin.events.furniture.wired.WiredStackExecutedEvent;
import com.eu.habbo.plugin.events.furniture.wired.WiredStackTriggeredEvent;
import com.eu.habbo.plugin.events.users.UserWiredRewardReceived;
import gnu.trove.map.hash.THashMap;
import gnu.trove.set.hash.THashSet;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WiredHandler {
    //Configuration. Loaded from database & updated accordingly.
    public static int MAXIMUM_FURNI_SELECTION = 5;
    public static int TELEPORT_DELAY = 500;

    public static boolean handle(WiredTriggerType triggerType, RoomUnit roomUnit, Room room, Object[] stuff) {
        boolean talked = false;

        if (room == null)
            return false;

        if (!room.isLoaded())
            return false;

        if (room.getRoomSpecialTypes() == null)
            return false;

        THashSet<InteractionWiredTrigger> triggers = room.getRoomSpecialTypes().getTriggers(triggerType);

        if (triggers == null || triggers.isEmpty())
            return false;

        List<RoomTile> triggeredTiles = new ArrayList<>();
        for (InteractionWiredTrigger trigger : triggers) {
            RoomTile tile = room.getLayout().getTile(trigger.getX(), trigger.getY());

            if (triggeredTiles.contains(tile))
                continue;

            if (handle(trigger, roomUnit, room, stuff)) {
                if (triggerType.equals(WiredTriggerType.SAY_SOMETHING))
                    talked = true;

                triggeredTiles.add(tile);
            }
        }

        return talked;
    }

    public static boolean handle(InteractionWiredTrigger trigger, final RoomUnit roomUnit, final Room room, final Object[] stuff) {
        if (trigger.execute(roomUnit, room, stuff)) {
            trigger.activateBox(room);

            THashSet<InteractionWiredCondition> conditions = room.getRoomSpecialTypes().getConditions(trigger.getX(), trigger.getY());
            THashSet<InteractionWiredEffect> effects = room.getRoomSpecialTypes().getEffects(trigger.getX(), trigger.getY());

            if (Emulator.getPluginManager().fireEvent(new WiredStackTriggeredEvent(room, roomUnit, trigger, effects, conditions)).isCancelled())
                return false;

            for (InteractionWiredCondition condition : conditions) {
                if (condition.execute(roomUnit, room, stuff)) {
                    condition.activateBox(room);
                } else {
                    if (!Emulator.getPluginManager().fireEvent(new WiredConditionFailedEvent(room, roomUnit, trigger, condition)).isCancelled())
                        return false;
                }
            }


            boolean hasExtraRandom = room.getRoomSpecialTypes().hasExtraType(trigger.getX(), trigger.getY(), WiredExtraRandom.class);
            boolean hasExtraUnseen = room.getRoomSpecialTypes().hasExtraType(trigger.getX(), trigger.getY(), WiredExtraUnseen.class);
            THashSet<InteractionWiredExtra> extras = room.getRoomSpecialTypes().getExtras(trigger.getX(), trigger.getY());

            for (InteractionWiredExtra extra : extras) {
                extra.activateBox(room);
            }

            List<InteractionWiredEffect> effectList = new ArrayList<>(effects);

            if (hasExtraRandom || hasExtraUnseen) {
                Collections.shuffle(effectList);
            }

            long millis = System.currentTimeMillis();
            if (hasExtraUnseen) {
                for (InteractionWiredExtra extra : room.getRoomSpecialTypes().getExtras(trigger.getX(), trigger.getY())) {
                    if (extra instanceof WiredExtraUnseen) {
                        extra.setExtradata(extra.getExtradata().equals("1") ? "0" : "1");

                        InteractionWiredEffect effect = ((WiredExtraUnseen) extra).getUnseenEffect(effectList);

                        if (effect != null) {
                            triggerEffect(effect, roomUnit, room, stuff, millis);
                            break;
                        }
                    }
                }
            } else {
                for (final InteractionWiredEffect effect : effectList) {
                    boolean executed = triggerEffect(effect, roomUnit, room, stuff, millis);
                    if (hasExtraRandom && executed) {
                        break;
                    }
                }
            }

            return !Emulator.getPluginManager().fireEvent(new WiredStackExecutedEvent(room, roomUnit, trigger, effects, conditions)).isCancelled();
        }

        return false;
    }

    private static boolean triggerEffect(InteractionWiredEffect effect, RoomUnit roomUnit, Room room, Object[] stuff, long millis) {
        boolean executed = false;
        if (effect.canExecute(millis)) {
            executed = true;
            if (!effect.requiresTriggeringUser() || (roomUnit != null && effect.requiresTriggeringUser())) {
                Emulator.getThreading().run(new Runnable() {
                    @Override
                    public void run() {
                        if (room.isLoaded()) {
                            try {
                                effect.execute(roomUnit, room, stuff);
                            } catch (Exception e) {
                                Emulator.getLogging().logErrorLine(e);
                            }

                            effect.activateBox(room);
                        }
                    }
                }, effect.getDelay() * 500);
            }
        }

        return executed;
    }


    public static boolean executeEffectsAtTiles(THashSet<RoomTile> tiles, final RoomUnit roomUnit, final Room room, final Object[] stuff) {
        for (RoomTile tile : tiles) {
            if (room != null) {
                THashSet<HabboItem> items = room.getItemsAt(tile);

                long millis = System.currentTimeMillis();
                for (final HabboItem item : items) {
                    if (item instanceof InteractionWiredEffect) {
                        triggerEffect((InteractionWiredEffect) item, roomUnit, room, stuff, millis);
                    }
                }
            }
        }

        return true;
    }

    public static void dropRewards(int wiredId) {
        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("DELETE FROM wired_rewards_given WHERE wired_item = ?")) {
            statement.setInt(1, wiredId);
            statement.execute();
        } catch (SQLException e) {
            Emulator.getLogging().logSQLException(e);
        }
    }

    private static void giveReward(Habbo habbo, WiredEffectGiveReward wiredBox, WiredGiveRewardItem reward) {
        if (wiredBox.limit > 0)
            wiredBox.given++;

        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("INSERT INTO wired_rewards_given (wired_item, user_id, reward_id, timestamp) VALUES ( ?, ?, ?, ?)")) {
            statement.setInt(1, wiredBox.getId());
            statement.setInt(2, habbo.getHabboInfo().getId());
            statement.setInt(3, reward.id);
            statement.setInt(4, Emulator.getIntUnixTimestamp());
            statement.execute();
        } catch (SQLException e) {
            Emulator.getLogging().logSQLException(e);
        }

        if (reward.badge) {
            UserWiredRewardReceived rewardReceived = new UserWiredRewardReceived(habbo, wiredBox, "badge", reward.data);
            if (Emulator.getPluginManager().fireEvent(rewardReceived).isCancelled())
                return;

            if (rewardReceived.value.isEmpty())
                return;

            HabboBadge badge = new HabboBadge(0, rewardReceived.value, 0, habbo);
            Emulator.getThreading().run(badge);
            habbo.getInventory().getBadgesComponent().addBadge(badge);
            habbo.getClient().sendResponse(new AddUserBadgeComposer(badge));
            habbo.getClient().sendResponse(new WiredRewardAlertComposer(WiredRewardAlertComposer.REWARD_RECEIVED_BADGE));
        } else {
            String[] data = reward.data.split("#");

            if (data.length == 2) {
                UserWiredRewardReceived rewardReceived = new UserWiredRewardReceived(habbo, wiredBox, data[0], data[1]);
                if (Emulator.getPluginManager().fireEvent(rewardReceived).isCancelled())
                    return;

                if (rewardReceived.value.isEmpty())
                    return;

                if (rewardReceived.type.equalsIgnoreCase("seasonal")) {
                    int seasonal = Integer.valueOf(rewardReceived.value);
                    int type = Emulator.getConfig().getInt("seasonal.primary.type");

                    habbo.givePoints(type, seasonal);
                } else if (rewardReceived.type.equalsIgnoreCase("furni")) {
                    Item baseItem = Emulator.getGameEnvironment().getItemManager().getItem(Integer.valueOf(rewardReceived.value));
                    if (baseItem != null) {
                        HabboItem item = Emulator.getGameEnvironment().getItemManager().createItem(habbo.getHabboInfo().getId(), baseItem, 0, 0, "");

                        if (item != null) {
                            habbo.getClient().sendResponse(new AddHabboItemComposer(item));
                            habbo.getClient().getHabbo().getInventory().getItemsComponent().addItem(item);
                            habbo.getClient().sendResponse(new PurchaseOKComposer(null));
                            habbo.getClient().sendResponse(new InventoryRefreshComposer());
                            habbo.getClient().sendResponse(new WiredRewardAlertComposer(WiredRewardAlertComposer.REWARD_RECEIVED_ITEM));
                        }
                    }
                } else if (rewardReceived.type.equalsIgnoreCase("goto")) {
                    String roomIdString = rewardReceived.value;
                    int roomId = Integer.parseInt(roomIdString);
                    if (roomId > 0) {
                        if (roomId > 0) {
                            habbo.getClient().sendResponse(new ForwardToRoomComposer(roomId));
                        }
                    }
                } else if (rewardReceived.type.equalsIgnoreCase("points")) {
                    String eventType = rewardReceived.value;
                    if (habbo != null) {

                        int type = Emulator.getConfig().getInt("seasonal.primary.type");
                        habbo.givePoints(type, Integer.valueOf(Emulator.getConfig().getInt("cmd.points.amount.event")));

                        THashMap<String, String> keys = new THashMap<String, String>();
                        keys.put("display", "BUBBLE");
                        keys.put("image", "${image.library.url}notifications/diamonds.png");
                        keys.put("message", Emulator.getTexts().getValue("commands.generic.cmd_points.received").replace("%type%", eventType));

                        habbo.getClient().sendResponse(new BubbleAlertComposer(BubbleAlertKeys.RECEIVED_BADGE.key, keys));

                        THashMap<String, String> keysWin = new THashMap<String, String>();
                        keysWin.put("display", "BUBBLE");
                        keysWin.put("image", "https://www.mania.gg/api/head/" + habbo.getHabboInfo().getUsername() + ".png");
                        keysWin.put("message", Emulator.getTexts().getValue("commands.generic.cmd_points.winner").replace("%user%", habbo.getHabboInfo().getUsername()).replace("%type%", eventType));

                        Emulator.getGameServer().getGameClientManager().sendBroadcastResponse(new BubbleAlertComposer(BubbleAlertKeys.RECEIVED_BADGE.key, keysWin));
                        habbo.getClient().updatePoints(eventType);
                    }
                } else if (rewardReceived.type.equalsIgnoreCase("respect")) {
                    habbo.getHabboStats().respectPointsReceived += Integer.valueOf(rewardReceived.value);
                } else if (rewardReceived.type.equalsIgnoreCase("cata")) {
                    CatalogItem item = Emulator.getGameEnvironment().getCatalogManager().getCatalogItem(Integer.valueOf(rewardReceived.value));

                    if (item != null) {
                        Emulator.getGameEnvironment().getCatalogManager().purchaseItem(null, item, habbo, 1, "", true);
                    }
                    habbo.getClient().sendResponse(new WiredRewardAlertComposer(WiredRewardAlertComposer.REWARD_RECEIVED_ITEM));
                }
            } else if (data.length == 3) {
                UserWiredRewardReceived rewardReceived = new UserWiredRewardReceived(habbo, wiredBox, data[0], data[1], data[2]);

                if (Emulator.getPluginManager().fireEvent(rewardReceived).isCancelled())
                    return;

                if (rewardReceived.value.isEmpty())
                    return;

                if (rewardReceived.type.equalsIgnoreCase("furnialert")) {
                    Item baseItem = Emulator.getGameEnvironment().getItemManager().getItem(Integer.valueOf(rewardReceived.value));
                    if (baseItem != null) {
                        HabboItem item = Emulator.getGameEnvironment().getItemManager().createItem(habbo.getHabboInfo().getId(), baseItem, 0, 0, "");

                        if (item != null) {
                            habbo.getClient().sendResponse(new AddHabboItemComposer(item));
                            habbo.getClient().getHabbo().getInventory().getItemsComponent().addItem(item);
                            habbo.getClient().sendResponse(new PurchaseOKComposer(null));
                            habbo.getClient().sendResponse(new InventoryRefreshComposer());
                            habbo.getClient().sendResponse(new WiredRewardAlertComposer(WiredRewardAlertComposer.REWARD_RECEIVED_ITEM));

                            THashMap<String, String> keysWin = new THashMap<String, String>();
                            keysWin.put("display", "BUBBLE");
                            keysWin.put("image", "${flash.dynamic.download.url}icons/" + item.getBaseItem().getName() + "_icon.png");
                            keysWin.put("message", rewardReceived.message.replace("%user%", habbo.getHabboInfo().getUsername()).replace("%room%", habbo.getHabboInfo().getCurrentRoom().getName()));

                            Emulator.getGameServer().getGameClientManager().sendBroadcastResponse(new BubbleAlertComposer(BubbleAlertKeys.RECEIVED_BADGE.key, keysWin));
                        }
                    }
                }
            }
        }
    }

    public static boolean getReward(Habbo habbo, WiredEffectGiveReward wiredBox) {
        if (wiredBox.limit > 0) {
            if (wiredBox.limit - wiredBox.given == 0) {
                habbo.getClient().sendResponse(new WiredRewardAlertComposer(WiredRewardAlertComposer.LIMITED_NO_MORE_AVAILABLE));
                return false;
            }
        }

        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) as nrows, wired_rewards_given.* FROM wired_rewards_given WHERE user_id = ? AND wired_item = ? ORDER BY timestamp DESC LIMIT ?")) {
            statement.setInt(1, habbo.getHabboInfo().getId());
            statement.setInt(2, wiredBox.getId());
            statement.setInt(3, wiredBox.rewardItems.size());

            try (ResultSet set = statement.executeQuery()) {
                if (set.first()) {
                    set.beforeFirst();
                    if (set.next()) {
                        if (wiredBox.rewardTime == WiredEffectGiveReward.LIMIT_N_MINUTES) {
                            if (Emulator.getIntUnixTimestamp() - set.getInt("timestamp") <= 60) {
                                habbo.getClient().sendResponse(new WiredRewardAlertComposer(WiredRewardAlertComposer.REWARD_ALREADY_RECEIVED_THIS_MINUTE));
                                return false;
                            }
                        }

                        if (wiredBox.rewardTime == WiredEffectGiveReward.LIMIT_N_HOURS) {
                            if (!(Emulator.getIntUnixTimestamp() - set.getInt("timestamp") >= (3600 * wiredBox.limitationInterval))) {
                                habbo.getClient().sendResponse(new WiredRewardAlertComposer(WiredRewardAlertComposer.REWARD_ALREADY_RECEIVED_THIS_HOUR));
                                return false;
                            }
                        }

                        if (wiredBox.rewardTime == WiredEffectGiveReward.LIMIT_N_DAY) {
                            if (!(Emulator.getIntUnixTimestamp() - set.getInt("timestamp") >= (86400 * wiredBox.limitationInterval))) {
                                habbo.getClient().sendResponse(new WiredRewardAlertComposer(WiredRewardAlertComposer.REWARD_ALREADY_RECEIVED_THIS_TODAY));
                                return false;
                            }
                        }

                        if (wiredBox.uniqueRewards) {
                            if (set.getInt("nrows") == wiredBox.rewardItems.size()) {
                                habbo.getClient().sendResponse(new WiredRewardAlertComposer(WiredRewardAlertComposer.REWARD_ALL_COLLECTED));
                                return false;
                            }
                        }

                        if (set.getInt("nrows") >= wiredBox.limitationInterval) {
                            if (wiredBox.limit == 0) {
                                habbo.getClient().sendResponse(new WiredRewardAlertComposer(WiredRewardAlertComposer.REWARD_ALREADY_RECEIVED));
                                return false;
                            }
                        }
                    }

                    if (wiredBox.uniqueRewards) {
                        for (WiredGiveRewardItem item : wiredBox.rewardItems) {
                            set.beforeFirst();
                            boolean found = false;

                            while (set.next()) {
                                if (set.getInt("reward_id") == item.id)
                                    found = true;
                            }

                            if (!found) {
                                giveReward(habbo, wiredBox, item);
                                return true;
                            }
                        }
                    } else {
                        for (WiredGiveRewardItem item : wiredBox.rewardItems) {

                            float randomValue = (float) (Emulator.getRandom().nextInt(101) * 0.1 / 10);

                            float probability = (float) (item.probability * 0.1 / 10);

                            if (randomValue <= probability) {
                                giveReward(habbo, wiredBox, item);
                                return true;
                            }
                        }
                        habbo.getClient().sendResponse(new WiredRewardAlertComposer(WiredRewardAlertComposer.UNLUCKY_NO_REWARD));
                    }
                }
            }
        } catch (SQLException e) {
            Emulator.getLogging().logSQLException(e);
        }

        return false;
    }

    public static void resetTimers(Room room) {
        if (!room.isLoaded())
            return;

        THashSet<InteractionWiredTrigger> triggers = room.getRoomSpecialTypes().getTriggers(WiredTriggerType.AT_GIVEN_TIME);

        if (triggers != null) {
            for (InteractionWiredTrigger trigger : triggers) {
                ((WiredTriggerReset) trigger).resetTimer();
            }
        }

        room.setLastTimerReset(Emulator.getIntUnixTimestamp());
    }
}
