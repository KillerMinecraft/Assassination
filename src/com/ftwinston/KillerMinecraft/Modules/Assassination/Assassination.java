package com.ftwinston.KillerMinecraft.Modules.Assassination;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import com.ftwinston.KillerMinecraft.GameMode;
import com.ftwinston.KillerMinecraft.Helper;
import com.ftwinston.KillerMinecraft.Option;
import com.ftwinston.KillerMinecraft.PlayerFilter;
import com.ftwinston.KillerMinecraft.Configuration.ChoiceOption;
import com.ftwinston.KillerMinecraft.Configuration.NumericOption;
import com.ftwinston.KillerMinecraft.Configuration.TeamInfo;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.SkullType;
import org.bukkit.World.Environment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
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
	
	NumericOption setupPeriod, timeLimit;
	ChoiceOption<ScoreLimit> winningScore;
	
	@Override
	public int getMinPlayers() { return 4; }
	
	enum ScoreLimit
	{
		None(0),
		Fifty(50),
		OneHundred(100),
		OneHundredFifty(150),
		TwoHundred(200),
		TwoHundredFifty(250),
		ThreeHundred(300),
		ThreeHundredFifty(350);
		
		private ScoreLimit(int val) { this.val = val; }
		int val;
	}
	
	@Override
	public Option[] setupOptions()
	{
		setupPeriod = new NumericOption("Setup time (minutes)", 0, 5, Material.WORKBENCH, 2);
		
		winningScore = new ChoiceOption<ScoreLimit>("Score limit", ScoreLimit.TwoHundred);
		winningScore.addChoice("No limit", ScoreLimit.None, Material.BARRIER);
		winningScore.addChoice("50 points", ScoreLimit.Fifty, Material.STONE_SWORD);
		winningScore.addChoice("100 points", ScoreLimit.OneHundred, Material.STONE_SWORD);
		winningScore.addChoice("150 points", ScoreLimit.OneHundredFifty, Material.STONE_SWORD);
		winningScore.addChoice("200 points", ScoreLimit.TwoHundred, Material.STONE_SWORD);
		winningScore.addChoice("250 points", ScoreLimit.TwoHundredFifty, Material.STONE_SWORD);
		winningScore.addChoice("300 points", ScoreLimit.ThreeHundred, Material.STONE_SWORD);
		winningScore.addChoice("350 points", ScoreLimit.ThreeHundredFifty, Material.STONE_SWORD);
		
		timeLimit = new NumericOption("Time limit (minutes)", 0, 40, Material.WATCH, 20, 5);
		
		return new Option[] { setupPeriod, winningScore, timeLimit }; 
	}
	
	@Override
	public List<String> getHelpMessages(TeamInfo team)
	{
		LinkedList<String> messages = new LinkedList<String>();
		messages.add("Every player will be assigned a target to kill. You may only your target, or the player hunting you.");
		messages.add("You will be given a head that shows who your target is. Placing this will create an eye of ender, which will lead you to your target.");
		messages.add("Remember that someone else is hunting you! If you kill anyone other than your target or your hunter, you will die.");
		messages.add("When you kill your target, you are assigned their target, and the hunt continues.");
		messages.add("When you respawn, you will not have a target or a hunter, until another player dies - then you will be given both.");
		
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
	
	class PlayerInfo
	{
		public PlayerInfo(String name) { this.name = name; }
		
		String name;
		PlayerInfo hunter, target;
		int currentWrongKillScore = 1, lastWrongKillScore = 0;
		long nextEnderEyeTime = 0;
	}
	
	HashMap<String, PlayerInfo> playerInfo = new HashMap<>();
	LinkedList<PlayerInfo> queuedPlayers = new LinkedList<>();
	
	private Player getTargetOf(OfflinePlayer player)
	{
		PlayerInfo info = playerInfo.get(player.getName());
		return info.target == null ? null : Helper.getPlayer(info.target.name);
	}
	
	private Player getHunterOf(OfflinePlayer player)
	{
		PlayerInfo info = playerInfo.get(player.getName());
		return info.hunter == null ? null : Helper.getPlayer(info.hunter.name);
	}

	private void setTargetOf(OfflinePlayer hunter, OfflinePlayer target)
	{
		PlayerInfo hunterInfo = hunter == null ? null : playerInfo.get(hunter.getName());
		PlayerInfo targetInfo = target == null ? null : playerInfo.get(target.getName());
		
		if (target == null)
		{
			if (hunter == null)
				return;
		
			hunterInfo.target = null;
			
			if (hunter.isOnline())
			{
				Player online = (Player)hunter;
				removeTargetItem(online);
				online.sendMessage("You do not currently have a target.");
			}
		}
		else if (hunter == null)
		{
			targetInfo.hunter = null;
			
			if (target.isOnline())
				((Player)target).sendMessage("You are not currently being hunted by anyone.");
		}
		else
		{	
			boolean hadTarget = hunterInfo.target != null;
			
			if (hunterInfo.target != null)
				hunterInfo.target.hunter = null;
			hunterInfo.target = targetInfo;
			
			if (targetInfo.hunter != null)
				targetInfo.hunter.target = null;
			targetInfo.hunter = hunterInfo;
			
			if (hunter.isOnline())
			{
				Player online = (Player)hunter;
				removeTargetItem(online);
				giveTargetItem(online, target);
				online.sendMessage((hadTarget ? "You have a new target." : "You have a target.") + " Their head is in your inventory. Also, you are being hunted!");
			}
		}
	}

	private PlayerInfo removePlayerFromHunt(OfflinePlayer removing)
	{
		// this player must no longer feature in the hunt lists
		PlayerInfo info = playerInfo.get(removing.getName());
		
		String hunterName = info.hunter == null ? null : info.hunter.name;
		String targetName = info.target == null ? null : info.target.name;
		
		if (info.hunter != null)
			info.hunter.target = null;
		info.hunter = null;
		
		if (info.target != null)
			info.target.hunter = null;
		info.target = null;
		

		if (hunterName == null || targetName == null || hunterName.equals(targetName))
			return info;
		
		// whoever hunted this player must now hunt their target
		Player hunter = Helper.getPlayer(hunterName);
		Player target = Helper.getPlayer(targetName);
		
		LinkedList<PlayerInfo> requeuedPlayers = new LinkedList<>();
		
		// additionally, all queued players should also be added in
		while (!queuedPlayers.isEmpty())
		{
			PlayerInfo queuedInfo = queuedPlayers.pop();
			Player queued = Helper.getPlayer(queuedInfo.name);
			if (queued == null)
				continue;
			
			// skip dead players, because we can't yet give them a target item
			if (queued.isDead())
			{
				requeuedPlayers.addFirst(queuedInfo);
				continue;
			}
			
			setTargetOf(hunter, queued);
			hunter = queued;
		}
		
		setTargetOf(hunter, target);
		queuedPlayers = requeuedPlayers;
		
		return info;
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
		playerInfo.clear();
		recentKills.clear();
		initializeKillTypes();		
		
		List<Player> players = getOnlinePlayers();
	
		for ( Player player : players )
		{
			Score score = scores.getScore(player.getName());
			score.setScore(0);
			
			playerInfo.put(player.getName(), new PlayerInfo(player.getName()));
		}
		
		long setupDelay = setupPeriod.getValue() == 0 ? 200 : ticksPerMinute * setupPeriod.getValue();
		
		allocationProcessID = getPlugin().getServer().getScheduler().scheduleSyncDelayedTask(getPlugin(), new Runnable() {
			public void run()
			{
				allocateTargets();
				
				int runTime = timeLimit.getValue();
				if (runTime == 0)
				{
					allocationProcessID = -1;
					return;
				}
				
				allocationProcessID = getPlugin().getServer().getScheduler().scheduleSyncDelayedTask(getPlugin(), new Runnable() {
					public void run()
					{
						timeLimitExpired();
					}
				}, runTime * ticksPerMinute);
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
			
			prevOne = current;
		}
		
		setTargetOf(prevOne, firstOne);
		
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
			player.sendMessage("You have a target: their head is in your inventory. Also, you are being hunted!");
		else
		{
			player.sendMessage("Nobody is currently hunting you. You will get a target as soon as someone else dies.");
			
			PlayerInfo info = playerInfo.get(player.getName());
			
			queuedPlayers.push(info);
		}
		return;
	}
	
	@Override
	public void playerJoinedLate(Player player)
	{
		Score score = scores.getScore(player.getName());
		score.setScore(0);

		player.sendMessage("Nobody is currently hunting you. You will get a target as soon as someone else dies.");
		playerInfo.put(player.getName(), new PlayerInfo(player.getName()));
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
	
	public void timeLimitExpired()
	{
		// TODO: work out who had the highest score, and print a message saying that
		broadcastMessage("The time limit has elapsed");
		finishGame();
	}
	
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void entityDamaged(EntityDamageEvent event)
	{
		if (!(event.getEntity() instanceof Player))
			return;
		
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

		PlayerInfo victimInfo = removePlayerFromHunt(victim);
		queuedPlayers.push(victimInfo);
		
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
			
			if (winningScore.getValue().val != 0 && score.getScore() >= winningScore.getValue().val)
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
		else
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
			this.name = ChatColor.GRAY + name;
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

	private int getPointsForWrongKill(Player attacker)
	{
		PlayerInfo info = playerInfo.get(attacker.getName());
		
		// calculate points to award based on Fibonacci sequence
		int value = info.lastWrongKillScore + info.currentWrongKillScore;
		info.lastWrongKillScore = info.currentWrongKillScore;
		info.currentWrongKillScore = value;
		
		return value;
	}
	
	static final String skullNamePrefix = "Target: ";
	private boolean isTargetSkull(ItemStack stack)
	{	
		if (!stack.hasItemMeta())
			return false;
		
		SkullMeta meta = (SkullMeta)stack.getItemMeta();
		return meta.getDisplayName().startsWith(skullNamePrefix);
	}
	
	private void removeTargetItem(Player player)
	{
		// remove all skull items that are named with the given prefix, to be sure they're from this game mode.  
		HashMap<Integer, ? extends ItemStack> matches = player.getInventory().all(Material.SKULL_ITEM);
		
		for (Integer key : matches.keySet())
		{
			ItemStack stack = matches.get(key);
			if (isTargetSkull(stack))
				player.getInventory().clear(key.intValue());
		}
	}

	private void giveTargetItem(Player hunter, OfflinePlayer target)
	{
		ItemStack skull = new ItemStack(Material.SKULL_ITEM, 1, (byte)SkullType.PLAYER.ordinal());
        SkullMeta skullMeta = (SkullMeta)Bukkit.getItemFactory().getItemMeta(Material.SKULL_ITEM);
        skullMeta.setOwner(target.getName());
        
        skullMeta.setDisplayName(skullNamePrefix + ChatColor.YELLOW + target.getName());
        skullMeta.setLore(Arrays.asList("Score points for killing this player.", "To find them, place this item", "and follow the eye of ender."));
        
        skull.setItemMeta(skullMeta);
        
        hunter.getInventory().addItem(skull);
	}
	
	static final long enderEyeRepeatInterval = 100;
	
	@EventHandler(priority = EventPriority.HIGHEST)
	public void onEvent(BlockPlaceEvent event)
	{
		ItemStack stack = event.getItemInHand();
		if (stack.getType() != Material.SKULL_ITEM || !isTargetSkull(stack) || event.getPlayer() == null)
			return;
		
		event.setCancelled(true);
		
		Player target = getTargetOf(event.getPlayer());
		if (target == null)
			return;
		
		// if this has been done too recently, cancel
		PlayerInfo info = playerInfo.get(event.getPlayer().getName());
		long time = getWorld(0).getFullTime(); 
		
		if (info.nextEnderEyeTime > time)
		{
			event.getPlayer().sendMessage("You can't do that again so quickly");
			return;
		}
		
		info.nextEnderEyeTime = time + enderEyeRepeatInterval;
				
		// create eye of ender, leading to this player's target
		Helper.createFlyingEnderEye(event.getPlayer(), target.getLocation());
	}
	
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onDrop(PlayerDropItemEvent event)
	{
		// don't let players drop their target indicator
		ItemStack stack = event.getItemDrop().getItemStack();
		if (stack.getType() != Material.SKULL_ITEM)
			return;
		
		if (isTargetSkull(stack))
			event.setCancelled(true);
	}
	
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onEvent(InventoryClickEvent event) {
		ItemStack stack = event.getCurrentItem();
		if (stack == null || stack.getType() != Material.SKULL_ITEM || !isTargetSkull(stack))
			return;
		
		Inventory top = event.getView().getTopInventory();
        Inventory bottom = event.getView().getBottomInventory();

        // don't let players place the target indicator into any other inventory
        if (top != null && bottom != null && top.getType() != InventoryType.PLAYER)
        	if (event.getRawSlot() > top.getSize())
                event.setCancelled(true);
	}
	
	// TODO: [Option] Randomized names & skins, via this: https://bitbucket.org/inventivetalent/nicknamer/src/9c33d4419616ed8a71d9a4ee0dd88916229444a4/NickNamer/Bukkit/src/de/inventivegames/nickname/?at=master
	// TODO: [Option] expend coal/charcoal to use target item
}
