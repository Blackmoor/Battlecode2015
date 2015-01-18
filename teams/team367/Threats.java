package team367;

import java.util.Arrays;

import battlecode.common.*;

/*
 * Store the tiles threatened by the enemy towers and the HQ in an array for quick access
 * We only need to update this if the number of towers changes.
 * 
 * Also provides an interface to determine tile safety due to enemy units
 * These are cached so that calling a second time on this turn won't re-calculate.
 */
public class Threats {
	private RobotController rc;
	private Sensors sensors;
	public MapLocation enemyHQ;
	public int enemyHQAttackRange;
	public MapLocation[] enemyTowers;
	private boolean[][] threatened; //true if an enemy tower or HQ threatens this tile
	private boolean [] myTiles; //Adjacent tiles - set to 0 if the tile is safe, 1 if threatened but can retreat, 2 if threatened but retreat is pointless
	private int[] lastUpdated; //The turn we last updated the stats for myTiles
	
	public Threats(RobotController myrc, Sensors sense) {
		rc = myrc;
		sensors = sense;
		enemyHQ = rc.senseEnemyHQLocation();
		enemyTowers = null;
		myTiles = new boolean[Direction.values().length];
		lastUpdated = new int[Direction.values().length];
		Arrays.fill(lastUpdated, -1);
	}
	
	public void update() {		
		MapLocation[] current = rc.senseEnemyTowerLocations();
		
		if (enemyTowers == null || current.length != enemyTowers.length) {
			enemyTowers = current;
			if (enemyTowers.length >= 2)
				enemyHQAttackRange = GameConstants.HQ_BUFFED_ATTACK_RADIUS_SQUARED;
			else
				enemyHQAttackRange = RobotType.HQ.attackRadiusSquared;
			threatened = new boolean[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];
			for (MapLocation m: MapLocation.getAllMapLocationsWithinRadiusSq(enemyHQ, enemyHQAttackRange)) {
				threatened[cropX(m.x)][cropY(m.y)] = true;
			}
			
			for (MapLocation t: enemyTowers) {
				for (MapLocation m: MapLocation.getAllMapLocationsWithinRadiusSq(t, RobotType.TOWER.attackRadiusSquared)) {
					threatened[cropX(m.x)][cropY(m.y)] = true;
				}
			}
		}
	}
	
	/*
	 * A tile is considered threatened if an enemy can fire on it before we can move out from it
	 * This routine can be called for our own tile or adjacent tiles.
	 * When considering adjacent tiles we need to factor in the time it would take to move in and then move out
	 */
	public boolean isThreatened(MapLocation m) {
		//Check to see if we have a cached result
		int now = Clock.getRoundNum();
		MapLocation myLoc = rc.getLocation();
		boolean isAdjacent = m.isAdjacentTo(myLoc); 
		Direction d = myLoc.directionTo(m);
		if (isAdjacent) { // We cache the results for adjacent tiles
			if (lastUpdated[d.ordinal()] == now)
				return myTiles[d.ordinal()];
			else
				lastUpdated[d.ordinal()] = now;	
		}
		
		RobotType myType = rc.getType();
		Boolean result = false; // Whether this tile is threatened by either a unit or tower or HQ
		if (threatened[cropX(m.x)][cropY(m.y)]) {
			result = true;
		} else {
			result = false; //Assume it is safe - the code below will set it to threatened
			
			//Check for threat from enemy units
			//We might be able to move in, fire and move out before they act
			double core = rc.getCoreDelay();
			double weapon = rc.getWeaponDelay();
			double moveCost = (myLoc.directionTo(m).isDiagonal())?GameConstants.DIAGONAL_DELAY_MULTIPLIER:1;
			double supply = rc.getSupplyLevel();
			int turns = 0;
					
			if (!myLoc.equals(m)) {
				//Factor in move
				while (core >= 1) {
					core = Math.max(0, core-0.5);
					weapon = Math.max(0, weapon-0.5);
					if (supply > myType.supplyUpkeep) {
						supply -= myType.supplyUpkeep;
						core = Math.max(0, core-0.5);
						weapon = Math.max(0, weapon-0.5);
					} else {
						supply = 0;
					}
					turns++;
				}
	
				weapon = Math.max(myType.loadingDelay, weapon);
				core += moveCost*myType.movementDelay;
			} else { // Consider it safe if we have time to fire here and still move away
				while (weapon >= 1) {
					core = Math.max(0, core-0.5);
					weapon = Math.max(0, weapon-0.5);
					if (supply > myType.supplyUpkeep) {
						supply -= myType.supplyUpkeep;
						core = Math.max(0, core-0.5);
						weapon = Math.max(0, weapon-0.5);
					} else {
						supply = 0;
					}
					turns++;
				}
				core = Math.max(myType.cooldownDelay, core);
				weapon += myType.attackDelay;
			}
			
			//Move away
			while (core >= 1) {
				core = Math.max(0, core-0.5);
				weapon = Math.max(0, weapon-0.5);
				if (supply > myType.supplyUpkeep) {
					supply -= myType.supplyUpkeep;
					core = Math.max(0, core-0.5);
					weapon = Math.max(0, weapon-0.5);
				} else {
					supply = 0;
				}
				turns++;
			}
			
			for (RobotInfo u: sensors.enemies(SensorRange.VISIBLE)) {
				if (u.type == RobotType.MISSILE) { //Assume a missile can reach us
					result = true;
					break;
				}
				// Can an enemy fire from where it is, or can it advance and then fire before us?
				int enemyTurns = 0;
				core = Math.min(0, u.coreDelay);
				weapon = Math.min(0, u.weaponDelay);
				supply = u.supplyLevel;
				MapLocation enemyLoc = u.location;
				if (u.type.canAttack()) {
					if (enemyLoc.distanceSquaredTo(m) <= u.type.attackRadiusSquared) { // In fire range
						while (weapon >= 1) {
							core = Math.max(0, core-0.5);
							weapon = Math.max(0, weapon-0.5);
							if (supply > u.type.supplyUpkeep) {
								supply -= u.type.supplyUpkeep;
								core = Math.max(0, core-0.5);
								weapon = Math.max(0, weapon-0.5);
							} else {
								supply = 0;
							}
							enemyTurns++;
						}
						core = Math.max(u.type.cooldownDelay, core);
						weapon += u.type.attackDelay;
						
						if (enemyTurns <= turns) {
							result = true;
							break;
						}
					}
				}
			}
		}
		
		/*
		if (myType == RobotType.COMMANDER) {
			if (myTiles[d.ordinal()])
				System.out.println(d + " is threatened");
			else
				System.out.println(d + " is safe");
		}
		*/
		
		if (isAdjacent)
			myTiles[d.ordinal()] = result;
		
		return result;		
	}
	
	/*
	 * Work out if we overwhelm the enemy in this area
	 */
	public boolean overwhelms(MapLocation myLoc) {
		double enemyHealth = 0.0;
		double allyHealth = 0.0;
		RobotInfo[] units = sensors.units(SensorRange.LONG);
		int senseRange = rc.getType().sensorRadiusSquared * SensorRange.LONG.multiplier;
		
		for (MapLocation t: enemyTowers) {
			if (t.distanceSquaredTo(myLoc) <= senseRange)
				enemyHealth += RobotType.TOWER.maxHealth;
		}
		
		if (enemyHQ.distanceSquaredTo(myLoc) <= Math.max(enemyHQAttackRange, senseRange)) {
			double[] multiplier = { 1.0, 1.25, 1.25, 1.25, 2.0, 2.0, 5.0 };
			enemyHealth += RobotType.HQ.maxHealth * multiplier[enemyTowers.length];
		}
		
		//Add in myself - it is not returned in the sense data
		if (rc.getType().canAttack() && rc.getType().canMove() && !rc.getType().canMine())
			allyHealth += rc.getHealth();
		
		//Add hp from nearby units
		for (RobotInfo u: units) {
			double health = u.health;
			if (u.type.needsSupply() && u.supplyLevel < u.type.supplyUpkeep)
				health /= 2.0;
			if (u.team != rc.getTeam()) {	//enemies				
				if (u.type.canAttack()) {
					enemyHealth += health;
					if (u.type == RobotType.HQ) // We already added the max HP so take it off now we know the real value 
						enemyHealth -= RobotType.HQ.maxHealth;
					if (u.type == RobotType.TOWER)
						enemyHealth -= RobotType.TOWER.maxHealth;
				}
			} else { // allies
				if (u.type.canAttack() && u.type.canMove() && !u.type.canMine())
					allyHealth += health;
			}
		}
		rc.setIndicatorString(1, "ally Health = "+allyHealth+" enemy Health = "+enemyHealth);
		
		return (allyHealth >= enemyHealth * 2 && allyHealth > 20);
	}
	
	private int cropCoord(int val, int max) {
		return ((val % max) + max) % max;
	}
	
	private int cropX(int x) {
		return cropCoord(x, GameConstants.MAP_MAX_WIDTH);
	}
	
	private int cropY(int y) {
		return cropCoord(y, GameConstants.MAP_MAX_HEIGHT);
	}
}
