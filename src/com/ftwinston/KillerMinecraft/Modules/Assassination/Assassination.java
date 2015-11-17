package com.ftwinston.KillerMinecraft.Modules.Assassination;

import java.util.ArrayList;
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
	static final int kickableScoreThreshold = -5;
	
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

		if ( inWarmup )
			messages.add("Every player will soon be assigned a target to kill, which they must do without being seen by anyone else.");
		else
			messages.add("Every player has been assigned a target to kill, which they must do without being seen by anyone else.");

		messages.add("Your compass points towards your victim, and if anyone sees you kill them, you will die instead of them.");
		messages.add("Remember that someone else is hunting you! If you kill anyone other than your target or your hunter, you will die instead of them.");
		messages.add("When you kill your target, you are assigned their target, and the game continues until only one player remains alive.");
		
		return messages;
	}
	
	Objective playerLives;
	
	@Override
	public Scoreboard createScoreboard()
	{
		Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
		
		playerLives = scoreboard.registerNewObjective("lives", "dummy");
		playerLives.setDisplaySlot(DisplaySlot.SIDEBAR);
		playerLives.setDisplayName("Lives remaining");
		
		return scoreboard;
	}
	
	@Override
	public Environment[] getWorldsToGenerate() { return new Environment[] { Environment.NORMAL }; }

	@Override
	public boolean isLocationProtected(Location l, Player p)
	{
		return false;
	}
	
	ArrayList<String> queuedPlayers = new ArrayList<>();
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
		
		List<Player> players = getOnlinePlayers();
	
		for ( Player player : players )
		{
			Score score = playerLives.getScore(player.getName());
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
			queuedPlayers.add(player.getName());
		}
		return;
	}
	
	@Override
	public void playerJoinedLate(Player player)
	{
		Score score = playerLives.getScore(player.getName());
		score.setScore(0);

		player.sendMessage("Nobody is currently hunting you. You will get a target as soon as someone else dies.");
		queuedPlayers.add(player.getName());
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
		queuedPlayers.add(victim.getName());
		
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
			Score score = playerLives.getScore(attacker.getName());
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
			Score score = playerLives.getScore(attacker.getName());
			score.setScore(score.getScore() - points);
			
			attacker.damage(100);
			
			victimTarget.sendMessage("You killed " + ChatColor.YELLOW + victim.getName() + ChatColor.RESET + ", who was not your target or your hunter. You have been punished.");

			// if a player kills too many "randoms", they are out of the game
			if (score.getScore() < kickableScoreThreshold)
				Helper.makeSpectator(getGame(), attacker);
		}
	}

	private int getPointsForKill(DamageCause cause)
	{
		// TODO: for each cause, track the base "difficulty" and adjust based on how "popular" it has been
		return 1;
	}

	private int getPointsForWrongKill(Player attacker)
	{
		// TODO: track how many "wrong" kills a player has committed, and increment by one each time
		return 1;
	}
}
