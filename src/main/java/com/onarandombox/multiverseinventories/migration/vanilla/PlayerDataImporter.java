package com.onarandombox.multiverseinventories.migration.vanilla;

import com.dumptruckman.minecraft.util.Logging;
import com.onarandombox.multiverseinventories.MultiverseInventories;
import com.onarandombox.multiverseinventories.PlayerStats;
import com.onarandombox.multiverseinventories.WorldGroup;
import com.onarandombox.multiverseinventories.migration.MigrationException;
import com.onarandombox.multiverseinventories.profile.PlayerProfile;
import com.onarandombox.multiverseinventories.profile.ProfileType;
import com.onarandombox.multiverseinventories.profile.ProfileTypes;
import com.onarandombox.multiverseinventories.profile.container.ContainerType;
import com.onarandombox.multiverseinventories.share.Sharables;
import com.onarandombox.multiverseinventories.util.MinecraftTools;
import net.minecraft.server.v1_16_R3.NBTCompressedStreamTools;
import net.minecraft.server.v1_16_R3.NBTTagCompound;
import net.minecraft.server.v1_16_R3.NBTTagList;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_16_R3.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerDataImporter {
    private final MultiverseInventories plugin;
    private final String world;
    private final WorldGroup group;
    private final int[] dataVersions = new int[]{2230};

    public PlayerDataImporter(MultiverseInventories plugin, String world, WorldGroup group) {
        this.plugin = plugin;
        this.world = world;
        this.group = group;
    }

    private File getPlayerDataFolder() {
        return new File(this.plugin.getServer().getWorldContainer() + File.separator + this.world + File.separator + "playerdata");
    }

    private void parseInventoryItems(NBTTagList inventory, ItemStack[][] stacks, int size) {
        // this is the main inventory
        stacks[0] = MinecraftTools.fillWithAir(new ItemStack[size]);
        // this is the armor contents
        stacks[1] = MinecraftTools.fillWithAir(new ItemStack[PlayerStats.ARMOR_SIZE]);
        // this is the off hand item
        stacks[2] = MinecraftTools.fillWithAir(new ItemStack[1]);

        for (int i = 0; i < inventory.size() - 1; i++) {
            NBTTagCompound compound = (NBTTagCompound) inventory.get(i);
            if (!compound.isEmpty()) {
                byte inv;
                // this will not cause an NPE (it always appears in playerdata)
                byte slot = compound.getByte("Slot");

                // we need to check this since armor and off hand item are also stored in the inventory
                if (-1 < slot && slot < 36) {
                    inv = 0;
                } else if (99 < slot && slot < 104) {
                    inv = 1;
                    slot -= 100;
                } else if (slot == -106) {
                    inv = 2;
                    slot = 0;
                } else inv = -1;

                if (inv != -1) {
                    ItemStack stack = CraftItemStack.asBukkitCopy(net.minecraft.server.v1_16_R3.ItemStack.a(compound));
                    stacks[inv][slot] = stack;
                }
            }
        }
    }

    public void importData() throws MigrationException {
        File playerDataFolder = getPlayerDataFolder();

        for (File playerData: playerDataFolder.listFiles()) {
            try {
                Tag nbt = Tag.readFrom(new FileInputStream(playerData));
                NBTTagCompound nmsNbt = NBTCompressedStreamTools.a(new FileInputStream(playerData));

                int dataVersion;
                Tag dataVersionTag = nbt.findTagByName("DataVersion");

                boolean supported = false;
                dataVersion = (dataVersionTag != null) ? (int) dataVersionTag.getValue() : -1;

                for (int version : dataVersions) {
                    if (dataVersion == version) {
                        supported = true;
                        break;
                    }
                }

                PlayerProfile pp;
                UUID uuid = UUID.fromString(playerData.getName().substring(0, playerData.getName().indexOf('.')));
                OfflinePlayer player = this.plugin.getServer().getOfflinePlayer(uuid);
                ProfileType profileType;

                if (!supported) {
                    Logging.warning("Player: " + ((player.getName() != null) ? player.getName() : player.getUniqueId())
                            + "'s NBT uses an unsupported data version! We'll attempt to import it anyways, but it may not work!");
                }

                Tag gamemode = (this.plugin.getMVIConfig().isUsingGameModeProfiles()) ? nbt.findTagByName("playerGameType") : null;
                switch ((gamemode != null) ? (int) gamemode.getValue() : 0) {
                    default:
                        profileType = ProfileTypes.SURVIVAL;
                        break;
                    case 1:
                        profileType = ProfileTypes.CREATIVE;
                        break;
                    case 2:
                        profileType = ProfileTypes.ADVENTURE;
                        break;
                }

                Map<String, Tag> tags = new HashMap<>();
                pp = PlayerProfile.createPlayerProfile(ContainerType.GROUP, group.getName(), profileType, player);

                // this is for imports that only need one value. imports that need more than one are dealt with later
                for (Tag tag: (Tag[]) nbt.getValue()) {
                    if (tag.getName() == null) continue;
                    switch (tag.getName()) {
                        case "Air":
                            pp.set(Sharables.REMAINING_AIR, (int) (short) tag.getValue());
                            break;
                        case "EnderItems":
                            ItemStack[][] enderItems = new ItemStack[3][];
                            NBTTagList enderInv = (NBTTagList) nmsNbt.get("EnderItems");
                            parseInventoryItems(enderInv, enderItems, PlayerStats.ENDER_CHEST_SIZE);
                            pp.set(Sharables.ENDER_CHEST, enderItems[0]);
                            break;
                        case "FallDistance":
                            pp.set(Sharables.FALL_DISTANCE, (float) tag.getValue());
                            break;
                        case "Fire":
                            pp.set(Sharables.FIRE_TICKS, (int) (short) tag.getValue());
                            break;
                        case "foodExhaustionLevel":
                            pp.set(Sharables.EXHAUSTION, (float) tag.getValue());
                            break;
                        case "foodLevel":
                            pp.set(Sharables.FOOD_LEVEL, (int) tag.getValue());
                            break;
                        case "foodSaturationLevel":
                            pp.set(Sharables.SATURATION, (float) tag.getValue());
                            break;
                        case "Health":
                            pp.set(Sharables.HEALTH, (double) (float) tag.getValue());
                            break;
                        case "Inventory":
                            ItemStack[][] stacks = new ItemStack[3][];
                            NBTTagList inventory = (NBTTagList) nmsNbt.get("Inventory");
                            parseInventoryItems(inventory, stacks, PlayerStats.INVENTORY_SIZE);
                            pp.set(Sharables.INVENTORY, stacks[0]);
                            pp.set(Sharables.ARMOR, stacks[1]);
                            pp.set(Sharables.OFF_HAND, stacks[2][0]);
                            break;
                        case "XpLevel":
                            pp.set(Sharables.LEVEL, (int) tag.getValue());
                            break;
                        case "XpP":
                            pp.set(Sharables.EXPERIENCE, (float) tag.getValue());
                            break;
                        case "XpTotal":
                            pp.set(Sharables.TOTAL_EXPERIENCE, (int) tag.getValue());
                            break;
                        // the following will be dealt with later
                        case "Dimension":
                        case "Pos":
                        case "Rotation":
                        case "SpawnX":
                        case "SpawnY":
                        case "SpawnZ":
                        // the following are only present on the main world of CraftBukkit servers and derivatives
                        case "WorldUUIDLeast":
                        case "WorldUUIDMost":
                            tags.put(tag.getName(), tag);
                    }
                } // TODO: potion effects

                // bed_spawn
                Tag bedX, bedY, bedZ;
                bedX = tags.get("SpawnX");
                bedY = tags.get("SpawnY");
                bedZ = tags.get("SpawnZ");

                if (bedX != null && bedY != null && bedZ != null) {
                    pp.set(Sharables.BED_SPAWN, new Location(this.plugin.getServer().getWorld(world),
                            (int) bedX.getValue(), (int) bedY.getValue(), (int) bedZ.getValue()));
                }

                // last_location
                World w = null;
                Tag most, least;
                most = tags.get("WorldUUIDMost");
                least = tags.get("WorldUUIDLeast");

                if (most != null && least != null) {
                    w = this.plugin.getServer().getWorld(new UUID((long) most.getValue(), (long) least.getValue()));
                } else {
                    Tag dimension = tags.get("Dimension");

                    if (dimension != null) {
                        // have you ever looked into a Minecraft world and seen DIM-1 and DIM1 ;)
                        switch ((int) dimension.getValue()) {
                            default:
                                w = this.plugin.getServer().getWorld(world);
                                break;
                            case -1:
                                w = this.plugin.getServer().getWorld(world + "_nether");
                                break;
                            case 1:
                                w = this.plugin.getServer().getWorld(world + "_the_end");
                                break;
                        }
                    }
                }

                if (w != null && group.getWorlds().contains(w.getName())) {
                    double x, y, z;
                    float yaw, pitch;
                    Tag[] pos = (Tag[]) tags.get("Pos").getValue();
                    Tag[] rot = (Tag[]) tags.get("Rotation").getValue();

                    x = (double) pos[0].getValue();
                    y = (double) pos[1].getValue();
                    z = (double) pos[2].getValue();
                    yaw = (float) rot[0].getValue();
                    pitch = (float) rot[1].getValue();

                    pp.set(Sharables.LAST_LOCATION, new Location(w, x, y, z, yaw, pitch));
                }

                group.getGroupProfileContainer().addPlayerData(pp);
                this.plugin.getData().updatePlayerData(pp);
            } catch (Exception e) {
                throw new MigrationException("An unhandled exception occurred while importing playerdata.").setCauseException(e);
            }
        }
    }
}
