package net.minestom.server.inventory.click;

import net.minestom.server.entity.Player;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.inventory.InventoryClickEvent;
import net.minestom.server.event.inventory.InventoryPostClickEvent;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.PlayerInventory;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.packet.client.play.ClientClickWindowPacket;
import net.minestom.server.utils.inventory.PlayerInventoryUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntFunction;

public final class Click {

    /**
     * Contains information about a click. These are equal to the packet slot IDs from <a href="https://wiki.vg/Inventory">the Minecraft protocol.</a>.
     * The inventory used should be known from context.
     */
    public sealed interface Info {

        record Left(int slot) implements Info {}
        record Right(int slot) implements Info {}
        record Middle(int slot) implements Info {} // Creative only

        record LeftShift(int slot) implements Info {}
        record RightShift(int slot) implements Info {}

        record Double(int slot) implements Info {}

        record LeftDrag(List<Integer> slots) implements Info {}
        record RightDrag(List<Integer> slots) implements Info {}
        record MiddleDrag(List<Integer> slots) implements Info {} // Creative only

        record LeftDropCursor() implements Info {}
        record RightDropCursor() implements Info {}
        record MiddleDropCursor() implements Info {}

        record DropSlot(int slot, boolean all) implements Info {}

        record HotbarSwap(int hotbarSlot, int clickedSlot) implements Info {}
        record OffhandSwap(int slot) implements Info {}

        record CreativeSetItem(int slot, @NotNull ItemStack item) implements Info {}
        record CreativeDropItem(@NotNull ItemStack item) implements Info {}

    }

    /**
     * Preprocesses click packets, turning them into {@link Info} instances for further processing.
     */
    public static final class Preprocessor {

        private final List<Integer> leftDrag = new ArrayList<>();
        private final List<Integer> rightDrag = new ArrayList<>();
        private final List<Integer> middleDrag = new ArrayList<>();

        public void clearCache() {
            leftDrag.clear();
            rightDrag.clear();
            middleDrag.clear();
        }

        /**
         * Processes the provided click packet, turning it into a {@link Info}.
         * This will do simple verification of the packet before sending it to {@link #process(ClientClickWindowPacket.ClickType, int, byte, int)}.
         *
         * @param packet the raw click packet
         * @param isCreative whether or not the player is in creative mode (used for ignoring some actions)
         * @return the information about the click, or nothing if there was no immediately usable information
         */
        public @Nullable Click.Info process(@NotNull ClientClickWindowPacket packet, @NotNull Inventory inventory, boolean isCreative) {
            final int originalSlot = packet.slot();
            final byte button = packet.button();
            final ClientClickWindowPacket.ClickType type = packet.clickType();

            int slot = inventory instanceof PlayerInventory ? PlayerInventoryUtils.protocolToMinestom(originalSlot) : originalSlot;
            if (originalSlot == -999) slot = -999;

            boolean creativeRequired = switch (type) {
                case CLONE -> true;
                case QUICK_CRAFT -> button == 8 || button == 9 || button == 10;
                default -> false;
            };

            if (creativeRequired && !isCreative) return null;

            int maxSize = inventory.getSize() + (inventory instanceof PlayerInventory ? 0 : PlayerInventoryUtils.INNER_SIZE);
            return process(type, slot, button, maxSize);
        }

        /**
         * Processes a packet into click info.
         *
         * @param type     the type of the click
         * @param slot     the clicked slot
         * @param button   the sent button
         * @return the information about the click, or nothing if there was no immediately usable information
         */
        public @Nullable Click.Info process(@NotNull ClientClickWindowPacket.ClickType type,
                                            int slot, byte button, int maxSize) {
            boolean valid = slot >= 0 && slot < maxSize;
            return switch (type) {
                case PICKUP -> {
                    if (slot == -999) {
                        yield switch (button) {
                            case 0 -> new Info.LeftDropCursor();
                            case 1 -> new Info.RightDropCursor();
                            case 2 -> new Info.MiddleDropCursor();
                            default -> null;
                        };
                    }

                    if (!valid) yield null;

                    yield switch (button) {
                        case 0 -> new Info.Left(slot);
                        case 1 -> new Info.Right(slot);
                        default -> null;
                    };
                }
                case QUICK_MOVE -> {
                    if (!valid) yield null;
                    yield button == 0 ? new Info.LeftShift(slot) : new Info.RightShift(slot);
                }
                case SWAP -> {
                    if (!valid) {
                        yield null;
                    } else if (button >= 0 && button < 9) {
                        yield new Info.HotbarSwap(button, slot);
                    } else if (button == 40) {
                        yield new Info.OffhandSwap(slot);
                    } else {
                        yield null;
                    }
                }
                case CLONE -> valid ? new Info.Middle(slot) : null;
                case THROW -> valid ? new Info.DropSlot(slot, button == 1) : null;
                case QUICK_CRAFT -> {
                    // Handle drag finishes
                    if (button == 2) {
                        var list = List.copyOf(leftDrag);
                        leftDrag.clear();
                        yield new Info.LeftDrag(list);
                    } else if (button == 6) {
                        var list = List.copyOf(rightDrag);
                        rightDrag.clear();
                        yield new Info.RightDrag(list);
                    } else if (button == 10) {
                        var list = List.copyOf(middleDrag);
                        middleDrag.clear();
                        yield new Info.MiddleDrag(list);
                    }

                    Consumer<List<Integer>> tryAdd = list -> {
                        if (valid && !list.contains(slot)) {
                            list.add(slot);
                        }
                    };

                    switch (button) {
                        case 0 -> leftDrag.clear();
                        case 4 -> rightDrag.clear();
                        case 8 -> middleDrag.clear();

                        case 1 -> tryAdd.accept(leftDrag);
                        case 5 -> tryAdd.accept(rightDrag);
                        case 9 -> tryAdd.accept(middleDrag);
                    }

                    yield null;
                }
                case PICKUP_ALL -> valid ? new Info.Double(slot) : null;
            };
        }

    }

    /**
     * Handles different types of clicks by players in an inventory.
     * The inventory is provided to this handler in the case of handlers that don't have internal state and may manage
     * multiple inventories, but it's also possible to store the inventory yourself and control usages of it.
     */
    public interface Processor {

        /**
         * Processes a click, returning a result. This will call events for the click.
         * @param inventory the clicked inventory (could be a player inventory)
         * @param player the player who clicked
         * @param info the click info describing the click
         * @return the click result, or null if the click did not occur
         */
        default @Nullable Click.Result handleClick(@NotNull Inventory inventory, @NotNull Player player, @NotNull Click.Info info) {
            InventoryPreClickEvent preClickEvent = new InventoryPreClickEvent(player.getInventory(), inventory, player, info);
            EventDispatcher.call(preClickEvent);

            Info newInfo = preClickEvent.getClickInfo();

            if (!preClickEvent.isCancelled()) {
                Result changes = processClick(inventory, player, newInfo);

                InventoryClickEvent clickEvent = new InventoryClickEvent(player.getInventory(), inventory, player, newInfo, changes);
                EventDispatcher.call(clickEvent);

                if (!clickEvent.isCancelled()) {
                    Result newChanges = clickEvent.getChanges();

                    Result.handle(newChanges, player, inventory);

                    var postClickEvent = new InventoryPostClickEvent(player, inventory, newInfo, newChanges);
                    EventDispatcher.call(postClickEvent);

                    if (!info.equals(newInfo) || !changes.equals(newChanges)) {
                        inventory.update(player);
                        if (inventory != player.getInventory()) {
                            player.getInventory().update(player);
                        }
                    }

                    return newChanges;
                }
            }

            inventory.update(player);
            if (inventory != player.getInventory()) {
                player.getInventory().update(player);
            }
            return null;
        }

        /**
         * Processes a click, returning a result. This should be a pure function with no side effects.
         * @param inventory the clicked inventory (could be a player inventory)
         * @param player the player who clicked
         * @param info the click info describing the click
         * @return the click result
         */
        @NotNull Click.Result processClick(@NotNull Inventory inventory, @NotNull Player player, @NotNull Click.Info info);

    }

    public record Getter(@NotNull IntFunction<ItemStack> main, @NotNull IntFunction<ItemStack> player,
                         @NotNull ItemStack cursor, int clickedSize) {

        public Getter(@NotNull Inventory clickedInventory, @NotNull Player player) {
            this(clickedInventory::getItemStack, player.getInventory()::getItemStack, player.getInventory().getCursorItem(), clickedInventory.getSize());
        }

        public @NotNull ItemStack get(int slot) {
            if (slot < clickedSize()) {
                return main.apply(slot);
            } else {
                return getPlayer(PlayerInventoryUtils.protocolToMinestom(slot, clickedSize()));
            }
        }

        public @NotNull ItemStack getPlayer(int slot) {
            return player.apply(slot);
        }

        public @NotNull Click.Setter setter() {
            return new Setter(clickedSize);
        }
    }

    public static class Setter {

        private final Map<Integer, ItemStack> main = new HashMap<>();
        private final Map<Integer, ItemStack> player = new HashMap<>();
        private @Nullable ItemStack cursor;
        private @Nullable SideEffect sideEffect;

        private final int clickedSize;

        Setter(int clickedSize) {
            this.clickedSize = clickedSize;
        }

        public int clickedSize() {
            return clickedSize;
        }

        public @NotNull Setter set(int slot, @NotNull ItemStack item) {
            if (slot >= clickedSize()) {
                int converted = PlayerInventoryUtils.protocolToMinestom(slot, clickedSize());
                return setPlayer(converted, item);
            } else {
                main.put(slot, item);
                return this;
            }
        }

        public @NotNull Setter setPlayer(int slot, @NotNull ItemStack item) {
            player.put(slot, item);
            return this;
        }

        public @NotNull Setter cursor(@Nullable ItemStack newCursorItem) {
            this.cursor = newCursorItem;
            return this;
        }

        public @NotNull Setter sideEffects(@Nullable SideEffect sideEffect) {
            this.sideEffect = sideEffect;
            return this;
        }

        public @NotNull Click.Result build() {
            return new Result(main, player, cursor, sideEffect);
        }

    }

    /**
     * Stores changes that occurred or will occur as the result of a click.
     * @param changes the map of changes that will occur to the inventory
     * @param playerInventoryChanges the map of changes that will occur to the player inventory
     * @param newCursorItem the player's cursor item after this click. Null indicates no change
     * @param sideEffects the side effects of this click
     */
    public record Result(@NotNull Map<Integer, ItemStack> changes, @NotNull Map<Integer, ItemStack> playerInventoryChanges,
                         @Nullable ItemStack newCursorItem, @Nullable Click.SideEffect sideEffects) {

        public static @NotNull Result nothing() {
            return new Result(Map.of(), Map.of(), null, null);
        }

        public Result {
            changes = Map.copyOf(changes);
            playerInventoryChanges = Map.copyOf(playerInventoryChanges);
        }

        static void handle(@NotNull Result result, @NotNull Player player, @NotNull Inventory inventory) {
            for (var entry : result.changes().entrySet()) {
                inventory.setItemStack(entry.getKey(), entry.getValue());
            }

            for (var entry : result.playerInventoryChanges().entrySet()) {
                player.getInventory().setItemStack(entry.getKey(), entry.getValue());
            }

            if (result.newCursorItem() != null) {
                player.getInventory().setCursorItem(result.newCursorItem());
            }

            if (result.sideEffects() instanceof SideEffect.DropFromPlayer drop) {
                for (ItemStack item : drop.items()) {
                    player.dropItem(item);
                }
            }
        }

    }

    /**
     * Represents side effects that may occur as the result of an inventory click.
     */
    public sealed interface SideEffect {

        record DropFromPlayer(@NotNull List<ItemStack> items) implements SideEffect {

            public DropFromPlayer {
                items = List.copyOf(items);
            }

            public DropFromPlayer(@NotNull ItemStack @NotNull ... items) {
                this(List.of(items));
            }
        }
    }
}
