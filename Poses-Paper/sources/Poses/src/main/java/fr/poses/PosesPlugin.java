package fr.poses;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Poses : /sit, /crawl, /lay, /spin + s'asseoir en faisant clic droit sur une dalle ou un escalier.
 */
public class PosesPlugin extends JavaPlugin implements Listener, CommandExecutor {

    enum Kind { SIT, CRAWL, LAY, SPIN }

    static class State {
        final Kind kind;
        ArmorStand stand;     // SIT : siège invisible
        Location origin;      // LAY : point de départ
        Location barrier;     // CRAWL/LAY : bloc fantôme au-dessus de la tête
        Location standUp;     // SIT : où te remettre en te relevant
        String seatKey;       // SIT sur dalle/escalier : clé du siège

        State(Kind kind) {
            this.kind = kind;
        }
    }

    private final Map<UUID, State> states = new HashMap<>();
    private final Map<String, UUID> seats = new HashMap<>();
    private double seatOffset = -0.2;
    private long tickCount = 0;

    // Méthodes Paper récentes : appelées par réflexion pour ne pas casser la compilation si elles changent
    private Method setPoseMethod;
    private Method setRiptidingMethod;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        seatOffset = getConfig().getDouble("sit-offset", -0.2);

        try {
            setPoseMethod = Entity.class.getMethod("setPose", Pose.class, boolean.class);
        } catch (NoSuchMethodException ex) {
            getLogger().warning("Entity#setPose(Pose, boolean) introuvable : /crawl et /lay seront moins fidèles pour les autres joueurs.");
        }
        try {
            setRiptidingMethod = LivingEntity.class.getMethod("setRiptiding", boolean.class);
        } catch (NoSuchMethodException ex) {
            getLogger().warning("LivingEntity#setRiptiding(boolean) introuvable : /spin est désactivé.");
        }

        getServer().getPluginManager().registerEvents(this, this);
        for (String name : new String[] {"sit", "crawl", "lay", "spin"}) {
            if (getCommand(name) != null) getCommand(name).setExecutor(this);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(states.keySet())) {
            State s = states.remove(id);
            if (s != null) finish(Bukkit.getPlayer(id), s, true);
        }
    }

    private static Component c(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }

    // ---------------------------------------------------------------- Commandes

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cCommande réservée aux joueurs.");
            return true;
        }
        Kind k = switch (cmd.getName().toLowerCase()) {
            case "sit" -> Kind.SIT;
            case "crawl" -> Kind.CRAWL;
            case "lay" -> Kind.LAY;
            case "spin" -> Kind.SPIN;
            default -> null;
        };
        if (k == null) return false;

        State cur = states.get(p.getUniqueId());
        if (cur != null && cur.kind == k) { // même commande = on arrête
            stop(p, true);
            p.sendMessage("§7Tu te relèves.");
            return true;
        }
        if (cur != null) stop(p, true);

        switch (k) {
            case SIT -> {
                if (!checkGround(p)) return true;
                Location l = p.getLocation();
                if (seat(p, l, l.getYaw(), null)) {
                    p.sendMessage("§aTu t'assois. §7(Shift pour te lever)");
                } else {
                    p.sendMessage("§cImpossible de t'asseoir ici.");
                }
            }
            case CRAWL -> {
                if (!checkGround(p)) return true;
                startProne(p, Kind.CRAWL);
                p.sendMessage("§aTu rampes. §7(/crawl pour te relever)");
            }
            case LAY -> {
                if (!checkGround(p)) return true;
                startProne(p, Kind.LAY);
                p.sendMessage("§aTu t'allonges. §7(bouge ou Shift pour te relever)");
            }
            case SPIN -> {
                if (setRiptidingMethod == null) {
                    p.sendMessage("§c/spin n'est pas supporté sur cette version.");
                    return true;
                }
                State s = new State(Kind.SPIN);
                states.put(p.getUniqueId(), s);
                setRiptiding(p, true);
                p.sendMessage("§aTu tournes ! §7(/spin pour arrêter)");
            }
        }
        return true;
    }

    private boolean checkGround(Player p) {
        if (p.isInsideVehicle() || !p.isOnGround()) {
            p.sendMessage("§cTu dois être au sol.");
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- Sit

    /** Assoit le joueur : `top` = position des pieds si le joueur se tenait sur la surface (dessus du bloc). */
    private boolean seat(Player p, Location top, float yaw, String key) {
        World w = top.getWorld();
        Location seatLoc = top.clone().add(0, seatOffset, 0);
        ArmorStand stand = w.spawn(seatLoc, ArmorStand.class, a -> {
            a.setVisible(false);
            a.setGravity(false);
            a.setMarker(true);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setBasePlate(false);
            a.setPersistent(false); // ne sera jamais sauvegardé : pas de siège fantôme après un crash
        });

        Location pl = top.clone();
        pl.setYaw(yaw);
        pl.setPitch(p.getLocation().getPitch());
        p.teleport(pl);
        if (!stand.addPassenger(p)) {
            stand.remove();
            return false;
        }
        State s = new State(Kind.SIT);
        s.stand = stand;
        s.standUp = top.clone();
        s.seatKey = key;
        states.put(p.getUniqueId(), s);
        if (key != null) seats.put(key, p.getUniqueId());
        return true;
    }

    private static float yawOf(BlockFace f) {
        return switch (f) {
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> 270f;
            default -> 0f;
        };
    }

    /** Clic droit (main vide) sur une dalle ou un escalier : on s'assoit dessus. */
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (p.isSneaking() || !p.hasPermission("poses.use")) return;
        if (!p.getInventory().getItemInMainHand().getType().isAir()
                || !p.getInventory().getItemInOffHand().getType().isAir()) return;
        Block b = e.getClickedBlock();
        if (b == null) return;

        BlockData d = b.getBlockData();
        double top;
        float yaw = p.getLocation().getYaw();
        if (d instanceof Slab slab) {
            top = slab.getType() == Slab.Type.BOTTOM ? 0.5 : 1.0;
        } else if (d instanceof Stairs st) {
            if (st.getHalf() != Bisected.Half.BOTTOM) return;
            top = 0.5;
            yaw = yawOf(st.getFacing().getOppositeFace()); // on regarde vers le bas de l'escalier
        } else {
            return;
        }

        State cur = states.get(p.getUniqueId());
        if (cur != null && cur.kind == Kind.SIT) return;
        if (p.isInsideVehicle() || !b.getRelative(BlockFace.UP).isPassable()) return;

        e.setCancelled(true);
        String key = b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
        if (seats.containsKey(key)) {
            p.sendActionBar(c("§cPlace déjà occupée."));
            return;
        }
        if (cur != null) stop(p, true);
        Location l = new Location(b.getWorld(), b.getX() + 0.5, b.getY() + top, b.getZ() + 0.5);
        if (seat(p, l, yaw, key)) {
            p.sendActionBar(c("§aAssis. §7Shift pour te lever."));
        }
    }

    /** Le joueur se lève (Shift) : on retire le siège et on le remet sur la surface. */
    @EventHandler
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        State s = states.get(p.getUniqueId());
        if (s == null || s.kind != Kind.SIT || s.stand == null || !s.stand.equals(e.getDismounted())) return;
        Bukkit.getScheduler().runTask(this, () -> stop(p, true));
    }

    // ---------------------------------------------------------------- Crawl / Lay

    private void startProne(Player p, Kind kind) {
        State s = new State(kind);
        s.origin = p.getLocation();
        states.put(p.getUniqueId(), s);
        applyPose(p);
        updateBarrier(p, s);
    }

    /** Pose "nage" fixée côté serveur : les autres joueurs te voient ramper. */
    private void applyPose(Player p) {
        if (setPoseMethod != null) {
            try {
                setPoseMethod.invoke(p, Pose.SWIMMING, true);
                return;
            } catch (Exception ignored) {
                // repli ci-dessous
            }
        }
        p.setSwimming(true);
    }

    private void clearPose(Player p) {
        if (setPoseMethod != null) {
            try {
                setPoseMethod.invoke(p, Pose.STANDING, false);
            } catch (Exception ignored) {
                // rien
            }
        }
        p.setSwimming(false);
    }

    /**
     * Un bloc fantôme (barrière, visible uniquement par toi) est placé juste au-dessus de ta tête :
     * le jeu te met alors automatiquement en position de ramper, comme dans un tunnel d'un bloc.
     */
    private void updateBarrier(Player p, State s) {
        Location feet = p.getLocation();
        int by = (int) Math.floor(feet.getY() + 1.0E-3) + 1;
        Location b = new Location(feet.getWorld(), feet.getBlockX(), by, feet.getBlockZ());
        if (s.barrier != null && s.barrier.getWorld() == b.getWorld()
                && s.barrier.getBlockX() == b.getBlockX() && s.barrier.getBlockY() == b.getBlockY()
                && s.barrier.getBlockZ() == b.getBlockZ()) {
            return;
        }
        clearBarrier(p, s);
        if (b.getBlock().getType().isSolid()) return; // un vrai bloc fait déjà le travail
        p.sendBlockChange(b, Material.BARRIER.createBlockData());
        s.barrier = b;
    }

    private void clearBarrier(Player p, State s) {
        if (s.barrier != null && p != null) {
            p.sendBlockChange(s.barrier, s.barrier.getBlock().getBlockData());
        }
        s.barrier = null;
    }

    // ---------------------------------------------------------------- Spin

    private void setRiptiding(Player p, boolean on) {
        if (setRiptidingMethod == null || p == null) return;
        try {
            setRiptidingMethod.invoke(p, on);
        } catch (Exception ignored) {
            // rien
        }
    }

    // ---------------------------------------------------------------- Arrêt / nettoyage

    private void stop(Player p, boolean restore) {
        State s = states.remove(p.getUniqueId());
        if (s != null) finish(p, s, restore);
    }

    private void finish(Player p, State s, boolean restore) {
        if (s.seatKey != null) seats.remove(s.seatKey);
        switch (s.kind) {
            case SIT -> {
                if (s.stand != null) s.stand.remove();
                if (restore && p != null && p.isOnline() && !p.isDead() && s.standUp != null) {
                    Location up = s.standUp.clone();
                    up.setYaw(p.getLocation().getYaw());
                    up.setPitch(p.getLocation().getPitch());
                    p.teleport(up);
                }
            }
            case CRAWL, LAY -> {
                clearBarrier(p, s);
                if (p != null) clearPose(p);
            }
            case SPIN -> setRiptiding(p, false);
        }
    }

    private void tick() {
        if (states.isEmpty()) return;
        tickCount++;
        for (UUID id : new ArrayList<>(states.keySet())) {
            State s = states.get(id);
            if (s == null) continue;
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                states.remove(id);
                finish(null, s, false);
                continue;
            }
            switch (s.kind) {
                case SIT -> {
                    if (s.stand == null || !s.stand.isValid() || !p.isInsideVehicle()) stop(p, true);
                }
                case CRAWL -> {
                    updateBarrier(p, s);
                    refreshPose(p);
                }
                case LAY -> {
                    Location l = p.getLocation();
                    double dx = l.getX() - s.origin.getX();
                    double dz = l.getZ() - s.origin.getZ();
                    if (l.getWorld() != s.origin.getWorld() || dx * dx + dz * dz > 0.25) {
                        stop(p, true);
                        p.sendMessage("§7Tu te relèves.");
                        continue;
                    }
                    updateBarrier(p, s);
                    refreshPose(p);
                }
                case SPIN -> {
                    if (tickCount % 5 == 0) setRiptiding(p, true);
                }
            }
        }
    }

    private void refreshPose(Player p) {
        if (setPoseMethod == null) {
            p.setSwimming(true);
        } else if (tickCount % 10 == 0) {
            applyPose(p);
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        State s = states.get(e.getPlayer().getUniqueId());
        if (s != null && s.kind == Kind.LAY && e.isSneaking()) {
            stop(e.getPlayer(), true);
            e.getPlayer().sendMessage("§7Tu te relèves.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        stop(e.getPlayer(), true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        stop(e.getEntity(), false);
    }
}
