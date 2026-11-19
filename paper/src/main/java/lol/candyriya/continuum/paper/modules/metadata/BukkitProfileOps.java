package lol.candyriya.continuum.paper.modules.metadata;

import net.kyori.adventure.text.Component;

import org.bukkit.entity.Player;

import org.jetbrains.annotations.Nullable;

/**
 * Thin pass-through from {@link ProfileOps} to the live entity. Every call here is a
 * straight platform read or write — no decisions, no state. Invoked only on the
 * player's owning thread (server main thread on Paper, entity region thread on
 * Folia), which is where the module contract runs it anyway.
 */
public final class BukkitProfileOps implements ProfileOps {

    private final Player player;

    public BukkitProfileOps(Player player) {
        this.player = player;
    }

    @Override
    public String playerName() {
        return player.getName();
    }

    @Override
    public @Nullable Component displayName() {
        return player.displayName();
    }

    @Override
    public @Nullable Component listName() {
        return player.playerListName();
    }

    @Override
    public void setDisplayName(@Nullable Component displayName) {
        player.displayName(displayName);
    }

    @Override
    public void setListName(@Nullable Component playerListName) {
        player.playerListName(playerListName);
    }
}
