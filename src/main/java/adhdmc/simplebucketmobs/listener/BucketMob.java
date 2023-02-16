package adhdmc.simplebucketmobs.listener;

import adhdmc.simplebucketmobs.SimpleBucketMobs;
import adhdmc.simplebucketmobs.config.Config;
import adhdmc.simplebucketmobs.config.Texture;
import adhdmc.simplebucketmobs.util.Message;
import adhdmc.simplebucketmobs.util.Permission;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import org.bukkit.*;
import org.bukkit.craftbukkit.v1_19_R2.entity.CraftLivingEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;

public class BucketMob implements Listener {

    public static final NamespacedKey mobNBTKey = new NamespacedKey(SimpleBucketMobs.getPlugin(), "mob_nbt");

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void bucketMob(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!player.isSneaking()) return;
        if (!(event.getRightClicked() instanceof LivingEntity entity)) return;
        if (entity.getType() == EntityType.PLAYER) return;
        if (!Config.getInstance().getAllowedTypes().contains(entity.getType())) return;
        if (!(player.hasPermission(Permission.BUCKET_ALL.get()) || player.hasPermission(Permission.BUCKET_MOB.get() + entity.getType()))) {
            player.sendMessage(Message.ERROR_BUCKET_NO_PERMISSION.getParsedMessage());
            return;
        }
        // TODO: Health Threshold Requirement / Health Check Bypass Permission (Per Mob)
        // TODO: Check disallowed attributes.
        ItemStack bucket = player.getEquipment().getItemInMainHand();
        // TODO: Allow for different bucket types.
        if (bucket.getType() != Material.BUCKET) return;
        if (bucket.getItemMeta().getPersistentDataContainer().has(mobNBTKey)) {
            event.setCancelled(true);
            return;
        }

        ItemStack mobBucket = new ItemStack(Material.BUCKET);
        CompoundTag tag = serializeNBT(entity);
        String serializedNbt = tag.getAsString();

        ItemMeta meta = mobBucket.getItemMeta();
        PersistentDataContainer bucketPDC = meta.getPersistentDataContainer();
        bucketPDC.set(mobNBTKey, PersistentDataType.STRING, serializedNbt);

        meta.displayName(SimpleBucketMobs.getMiniMessage().deserialize(
                "<!i>" + Config.getInstance().getBucketTitle(),
                Placeholder.unparsed("type", entity.getType().toString())
        ));
        Texture.getInstance().setCustomData(entity.getType(), meta, tag);
        mobBucket.setItemMeta(meta);
        if (player.getGameMode() != GameMode.CREATIVE) bucket.subtract();
        player.getInventory().addItem(mobBucket);
        try {
            // TODO: Configurable?
            String soundName = "ENTITY_" + entity.getType() + "_HURT";
            player.playSound(player.getLocation(), Sound.valueOf(soundName), 0.75f, 1.0f);
        } catch (IllegalArgumentException ignored) { }
        entity.remove();
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void unbucketMob(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Location interactLoc = event.getInteractionPoint();
        if (interactLoc == null) return;
        Player player = event.getPlayer();
        ItemStack bucket = player.getEquipment().getItemInMainHand();
        if (bucket.getType() != Material.BUCKET) return;
        if (!bucket.getItemMeta().getPersistentDataContainer().has(mobNBTKey)) return;

        String serializedNbt = bucket.getItemMeta().getPersistentDataContainer().get(mobNBTKey, PersistentDataType.STRING);

        try { if (serializedNbt != null) applyNBT(interactLoc, serializedNbt); }
        catch (IOException | CommandSyntaxException e) {
            player.sendMessage(Message.ERROR_FAILED_DESERIALIZATION.getParsedMessage());
            e.printStackTrace();
            return;
        }
        catch (IllegalArgumentException e) {
            player.sendMessage(Message.ERROR_NO_BUCKET_MOB.getParsedMessage());
            e.printStackTrace();
            return;
        }

        // TODO: Make configurable.
        player.playSound(player.getLocation(), Sound.ITEM_BUCKET_FILL_POWDER_SNOW, 1.0f, 1.0f);

        if (player.getGameMode() != GameMode.CREATIVE) {
            bucket.subtract();
            player.getInventory().addItem(new ItemStack(Material.BUCKET));
        }
    }

    /**
     * Serializes the NBT Data from the LivingEntity.
     * @param e LivingEntity
     * @return String serialization of the LivingEntity.
     */
    private CompoundTag serializeNBT(LivingEntity e) {
        CompoundTag tag = new CompoundTag();
        ((CraftLivingEntity) e).getHandle().save(tag);
        return tag;
    }

    /**
     * Deserializes the NBT Data into the LivingEntity.
     * @param location Location to spawn Mob.
     * @param serializedNbt NBT as a String
     * @exception IllegalArgumentException Invalid Mob Type Found
     * @exception IOException Failed to read NBT Tags.
     * @exception CommandSyntaxException What.
     */
    private void applyNBT(Location location, String serializedNbt) throws IllegalArgumentException, IOException, CommandSyntaxException {
        CompoundTag tag = TagParser.parseTag(serializedNbt);
        Tag idTag = tag.get("id");
        // TODO: Maybe throw exception.
        if (idTag == null) return;
        String id = idTag.getAsString().split(":")[1].toUpperCase();
        EntityType mobType = EntityType.valueOf(id);
        Entity entity = location.getWorld().spawnEntity(location, mobType, CreatureSpawnEvent.SpawnReason.CUSTOM);
        CompoundTag newLoc = new CompoundTag();
        ((CraftLivingEntity) entity).getHandle().save(newLoc);
        tag.put("Motion", newLoc.get("Motion"));
        tag.put("Pos", newLoc.get("Pos"));
        tag.put("Rotation", newLoc.get("Rotation"));
        tag.put("UUID", newLoc.get("UUID"));
        ((CraftLivingEntity) entity).getHandle().load(tag);
    }

}
