package com.ftwinston.KillerMinecraft.Modules.Assassination;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import com.ftwinston.KillerMinecraft.GameMode;
import com.ftwinston.KillerMinecraft.Helper;
import com.ftwinston.KillerMinecraft.Option;
import com.ftwinston.KillerMinecraft.PlayerFilter;
import com.ftwinston.KillerMinecraft.Configuration.NumericOption;
import com.ftwinston.KillerMinecraft.Configuration.TeamInfo;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World.Environment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.Material;

public class Assassination extends GameMode
{
	static final long ticksPerMinute = 1200L;
	static final double hunterAdjacentKillDistanceSq = 144;
	static final int kickableScoreThreshold = -10;
	
	NumericOption setupPeriod, winningScore;
	
	@Override
	public int getMinPlayers() { return 4; }
	
	@Override
	public Option[] setupOptions()
	{
		setupPeriod = new NumericOption("Setup time (minutes)", 0, 5, Material.WATCH, 2);
		winningScore = new NumericOption(); // argh, the values aren't contiguous. Need to make this a choice instead. 
		
		return new Option[] { setupPeriod };
	}
	
	@Override
	public List<String> getHelpMessages(TeamInfo team)
	{
		LinkedList<String> messages = new LinkedList<String>();
		messages.add("Every player will be assigned a target to kill. You may only your target, or the player hunting you.");
		messages.add("Remember that someone else is hunting you! If you kill anyone other than your target or your hunter, you will die.");
		messages.add("When you kill your target, you are assigned their target, and the hunt continues.");
		
		return messages;
	}
	
	Objective scores, pointsPerKill;
	
	@Override
	public Scoreboard createScoreboard()
	{
		Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
		
		scores = scoreboard.registerNewObjective("points", "dummy");
		scores.setDisplaySlot(DisplaySlot.PLAYER_LIST);
		scores.setDisplayName("Player scores");
		
		pointsPerKill = scoreboard.registerNewObjective("perKill", "dummy");
		pointsPerKill.setDisplaySlot(DisplaySlot.SIDEBAR);
		pointsPerKill.setDisplayName("Points per Kill");
		
		return scoreboard;
	}
	
	@Override
	public Environment[] getWorldsToGenerate() { return new Environment[] { Environment.NORMAL }; }

	@Override
	public boolean isLocationProtected(Location l, Player p)
	{
		return false;
	}
	
	LinkedList<String> queuedPlayers = new LinkedList<>();
	HashMap<String, String> playerTargets = new HashMap<>();
	HashMap<String, String> playerHunters = new HashMap<>();
	
	private Player getTargetOf(OfflinePlayer player)
	{
		String name = playerTargets.get(player.getName());
		return Helper.getPlayer(name);
	}
	
	private Player getHunterOf(OfflinePlayer player)
	{
		String name = playerHunters.get(player.getName());
		return Helper.getPlayer(name);
	}

	private void setTargetOf(OfflinePlayer hunter, OfflinePlayer target)
	{
		if (target == null)
		{
			if (hunter == null)
				return;
			
			playerTargets.remove(hunter.getName());
			
			if (hunter.isOnline())
				((Player)hunter).sendMessage("You do not currently have a target.");
		}
		else if (hunter == null)
		{
			playerHunters.remove(target.getName());
			
			if (target.isOnline())
				((Player)target).sendMessage("You are not currently being hunted by anyone.");
		}
		else
		{
			String prev = playerTargets.put(hunter.getName(), target.getName());
			playerHunters.remove(prev);
			
			prev = playerHunters.put(target.getName(), hunter.getName());
			playerTargets.remove(prev);
			
			if (hunter.isOnline())
				((Player)hunter).sendMessage((prev == null ? "Your target is: " : "Your new target is: ") +  ChatColor.YELLOW + target.getName() + ChatColor.RESET + "!");
		}
	}
	
	private void removePlayerFromHunt(OfflinePlayer removing)
	{
		// this player must no longer feature in the hunt lists
		String hunterName = playerHunters.remove(removing.getName());
		String targetName = playerTargets.get(removing.getName());

		if (hunterName.equals(targetName))
			return;
		
		// whoever hunted this player must now hunt their target
		Player hunter = Helper.getPlayer(hunterName);
		Player target = Helper.getPlayer(targetName);
		
		// additionally, all queued players should also be added in
		while (!queuedPlayers.isEmpty())
		{
			String queuedName = queuedPlayers.pop();
			Player queued = Helper.getPlayer(queuedName);
			if (queued == null)
				continue;
			
			setTargetOf(hunter, queued);
			hunter = queued;
		}
		
		setTargetOf(hunter, target);
	}
	
	@Override
	public Location getSpawnLocation(Player player)
	{
		Location spawnPoint = Helper.randomizeLocation(getWorld(0).getSpawnLocation(), 0, 0, 0, 16, 0, 16);
		return Helper.getSafeSpawnLocationNear(spawnPoint);
	}
	
	boolean inWarmup = true;
	int allocationProcessID = -1;
	
	@Override
	public void gameStarted()
	{
		queuedPlayers.clear();
		playerTargets.clear();
		playerHunters.clear();
		recentKills.clear();
		initializeKillTypes();		
		
		List<Player> players = getOnlinePlayers();
	
		for ( Player player : players )
		{
			Score score = scores.getScore(player.getName());
			score.setScore(0);
		}
		
		long setupDelay = setupPeriod.getValue() == 0 ? 200 : ticksPerMinute * setupPeriod.getValue();
		
		allocationProcessID = getPlugin().getServer().getScheduler().scheduleSyncDelayedTask(getPlugin(), new Runnable() {
			public void run()
			{
				allocateTargets();
				allocationProcessID = -1;
			}
		}, setupDelay);
	}
	
	private void allocateTargets()
	{
		// give everyone a target, make them be someone else's target
		List<Player> players = getOnlinePlayers(new PlayerFilter());
		
		Player firstOne = players.remove(random.nextInt(players.size()));
		Player prevOne = firstOne;
		
		while ( players.size() > 0 )
		{
			
			Player current = players.remove(random.nextInt(players.size()));
			setTargetOf(prevOne, current);
			
			prevOne.getInventory().addItem(new ItemStack(Material.COMPASS, 1));
			prevOne = current;
		}
		
		setTargetOf(prevOne, firstOne);
		prevOne.getInventory().addItem(new ItemStack(Material.COMPASS, 1));
		
		broadcastMessage("All players have been allocated a target to kill");
		inWarmup = false;
	}
	
	@Override
	public void gameFinished()
	{		
		if ( allocationProcessID != -1 )
		{
			getPlugin().getServer().getScheduler().cancelTask(allocationProcessID);
			allocationProcessID = -1;
		}
	}
	
	@Override
	public void playerReconnected(Player player)
	{
		Player target = getTargetOf(player);
		if (target != null)
			player.sendMessage("Your target is: " +  ChatColor.YELLOW + target + ChatColor.RESET + "!");
		else
		{
			player.sendMessage("Nobody is currently hunting you. You will get a target as soon as someone else dies.");
			queuedPlayers.push(player.getName());
		}
		return;
	}
	
	@Override
	public void playerJoinedLate(Player player)
	{
		Score score = scores.getScore(player.getName());
		score.setScore(0);

		player.sendMessage("Nobody is currently hunting you. You will get a target as soon as someone else dies.");
		queuedPlayers.push(player.getName());
	}
	
	@Override
	public void playerQuit(OfflinePlayer player)
	{
		if ( hasGameFinished() )
			return;
	
		removePlayerFromHunt(player);
		
		List<Player> survivors = getOnlinePlayers(new PlayerFilter());
		
		if ( survivors.size() == 1 )
		{
			Player survivor = survivors.get(0);
			broadcastMessage(new PlayerFilter().exclude(survivor), survivor.getName() + " is the last man standing, and wins the game!");
			survivor.sendMessage("You are the last man standing: you win the game!");
			finishGame();
		}
		else if ( survivors.size() == 0 )
		{
			broadcastMessage("All players died, nobody wins!");
			finishGame();
		}
		else if ( survivors.size() == 3 )
		{
			broadcastMessage("Three players remain: everyone is now a legitimate target!");
		}
	}
	
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void entityDamaged(EntityDamageEvent event)
	{
		Player victim = (Player)event.getEntity();
		if (victim == null)
			return;
		
		Player attacker = Helper.getAttacker(event);
		
		if (inWarmup && attacker != null)
		{
			event.setCancelled(true);
			return;
		}
		
		if (event.getFinalDamage() < victim.getHealth())
			return;
		
		Player victimHunter = getHunterOf(victim);
		Player victimTarget = getTargetOf(victim);

		removePlayerFromHunt(victim);
		queuedPlayers.push(victim.getName());
		
		if (attacker == null)
		{
			if (victimHunter == null)
				return;

			double dx = victimHunter.getLocation().getX() - victim.getLocation().getX();
			double dz = victimHunter.getLocation().getZ() - victim.getLocation().getZ();
			double horizDistSq = (dx * dx) + (dz * dz); 
			
			// if the hunter was close enough, and no other player did this, count as a kill for them.
			// (this allows e.g. lava, drowning, mob kills)
			if (horizDistSq < hunterAdjacentKillDistanceSq)
				attacker = victimHunter;
			else
				return;
		}
		
		if (attacker == victimHunter)
		{
			// target kill, award points to hunter
			int points = getPointsForKill(event.getCause());
			Score score = scores.getScore(attacker.getName());
			score.setScore(score.getScore() + points);
			
			victimTarget.sendMessage("You killed " + ChatColor.YELLOW + victim.getName() + ChatColor.RESET + ", who was your target, and were awarded " + points + " points.");
			
			if (winningScore.getValue() != 0 && score.getScore() >= winningScore.getValue())
			{
				broadcastMessage(ChatColor.YELLOW + attacker.getName() + ChatColor.RESET + " wins the game!");
				finishGame();
			}
		}
		else if (attacker == victimTarget)
		{
			// self defense kill
			Player newHunter = getHunterOf(victim);
			victimTarget.sendMessage("You killed " + ChatColor.YELLOW + victim.getName() + ChatColor.RESET + ", who was your hunter. " + (newHunter == null ? "No one is currently hunting you." : "Someone else is hunting you now!"));
		}
		else if (attacker != victimHunter && attacker != victimTarget)
		{
			// wasn't a target kill or legit self defense
			// remove points from attacker, and also kill them
			int points = getPointsForWrongKill(attacker);
			Score score = scores.getScore(attacker.getName());
			score.setScore(score.getScore() - points);
			
			attacker.damage(100);
			
			victimTarget.sendMessage("You killed " + ChatColor.YELLOW + victim.getName() + ChatColor.RESET + ", who was not your target or your hunter. You have been punished.");

			// if a player kills too many "randoms", they are out of the game
			if (score.getScore() <= kickableScoreThreshold)
			{
				broadcastMessage(ChatColor.YELLOW + attacker.getName() + ChatColor.RESET + " made too many unauthorized kills, and has been eliminated.");
				Helper.makeSpectator(getGame(), attacker);
			}
		}
	}
	
	class KillType
	{
		String name;
		int baseValue, currentValue;
		float valueScale;
		boolean visible;
		
		public KillType(String name, int value, boolean visibleByDefault)
		{
			this.name = name;
			this.baseValue = value;
			this.currentValue = baseValue;
			this.valueScale = 1f;
			this.visible = visibleByDefault;
			
			killTypes.put(name, this);
		}
		
		static final String Explosion = "Explosion", Crushing = "Crushing", Drowning = "Drowning", Impaling = "Impaling", Hitting = "Hitting", Falling = "Falling", Shooting = "Shooting", Burning = "Burning", Lava = "Lava", Starving = "Starving", Suffocating = "Suffocating";
		static final String Lightning = "Lightning", Magic = "Magic", Custom = "Custom", Melting = "Melting", Poisoning = "Poisoning", Void = "Void", Wither = "Withering";
	}
	
	HashMap<String, KillType> killTypes;
	
	private void initializeKillTypes()
	{
		killTypes = new HashMap<>();
		
		new KillType("Explosion", 25, true);
		new KillType("Crushing", 50, true);
		new KillType("Drowning", 30, true);
		new KillType("Hitting", 10, true);
		new KillType("Shooting", 18, true);
		new KillType("Burning", 15, true);
		new KillType("Lava", 20, true);
		new KillType("Starving", 25, true);
		new KillType("Falling", 18, true);
		new KillType("Suffocating", 35, true);

		new KillType("Impaling", 30, false);
		new KillType("Poisoning", 25, false);
		new KillType("Lightning", 45, false);
		new KillType("Magic", 25, false);
		new KillType("Custom", 30, false);
		new KillType("Melting", 50, false);
		new KillType("Void", 25, false);
		new KillType("Withering", 35, false);
		
		updateKillValues(null);
	}
	
	private KillType getKillType(DamageCause cause)
	{
		switch (cause)
		{
		case BLOCK_EXPLOSION:
		case ENTITY_EXPLOSION:
			return killTypes.get(KillType.Explosion);
		case CONTACT:
		case THORNS:
			return killTypes.get(KillType.Impaling);
		case CUSTOM:
			return killTypes.get(KillType.Custom);
		case DROWNING:
			return killTypes.get(KillType.Drowning);
		case ENTITY_ATTACK:
			return killTypes.get(KillType.Hitting);
		case FALL:
			return killTypes.get(KillType.Falling);
		case FALLING_BLOCK:
			return killTypes.get(KillType.Crushing);
		case FIRE:
		case FIRE_TICK:
			return killTypes.get(KillType.Burning);
		case LAVA:
			return killTypes.get(KillType.Lava);
		case LIGHTNING:
			return killTypes.get(KillType.Lightning);
		case MAGIC:
			return killTypes.get(KillType.Magic);
		case MELTING:
			return killTypes.get(KillType.Melting);
		case POISON:
			return killTypes.get(KillType.Poisoning);
		case PROJECTILE:
			return killTypes.get(KillType.Shooting);
		case STARVATION:
			return killTypes.get(KillType.Starving);
		case SUFFOCATION:
			return killTypes.get(KillType.Suffocating);
		case VOID:
			return killTypes.get(KillType.Void);
		case WITHER:
			return killTypes.get(KillType.Wither);
		case SUICIDE:
			break;
		}
		return null;
	}

	private int getPointsForKill(DamageCause cause)
	{
		KillType killType = getKillType(cause);
		if (killType == null)
			return 0;
		
		int pointsForKill = killType.currentValue;
		
		updateKillValues(killType);
		
		return pointsForKill;
	}

	static final int recentKillStoreCount = 10;
	LinkedList<KillType> recentKills = new LinkedList<>();
	
	private void updateKillValues(KillType killTypeUsed)
	{
		if (killTypeUsed != null)
		{
			recentKills.addFirst(killTypeUsed);
			if (recentKills.size() > recentKillStoreCount)
				recentKills.removeLast();
		}
		
		for (KillType type : killTypes.values())
			type.valueScale = 1f;
		
		// reduce the value of the most recent kill type by 1/3, the second most recent by 1/4, etc. Cumulatively. 
		int reductionScale = 3;
		for (KillType type : recentKills)
		{
			type.valueScale -= 1f / reductionScale;
			reductionScale ++;
		}

		for (KillType type : killTypes.values())	
		{
			// calculate value to award
			type.currentValue = Math.max(1, (int)(type.baseValue * type.valueScale));
			
			if (!type.visible)
				continue;
			
			// update display indicator for players
			Score score = pointsPerKill.getScore(type.name);
			score.setScore(type.currentValue);	
		}
	}
	
	HashMap<String, Integer> wrongKills = new HashMap<String, Integer>();

	private int getPointsForWrongKill(Player attacker)
	{
		int numWrong; 
		if (wrongKills.containsKey(attacker))
			numWrong = wrongKills.get(attacker).intValue() + 1;
		else
			numWrong = 1;
		
		wrongKills.put(attacker.getName(), Integer.valueOf(numWrong));
		
		return numWrong * numWrong;
	}
	
	// TODO: Add "target" item, showing name & head
	// TODO: Using this item should launch ender eye towards target
	// TODO: [Option] Randomized names & skins, via this: https://bitbucket.org/inventivetalent/nicknamer/src/9c33d4419616ed8a71d9a4ee0dd88916229444a4/NickNamer/Bukkit/src/de/inventivegames/nickname/?at=master
	// TODO: [Option] expend coal/charcoal to use target item
}
