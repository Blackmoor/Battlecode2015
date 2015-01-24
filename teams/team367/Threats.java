package team367;

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
	public MapLocation enemyHQ;
	public MapLocation[] enemyTowers;
	public int enemyHQAttackRange;
	private boolean [] myTiles; //Adjacent tiles - set to 0 if the tile is safe, 1 if threatened but can retreat, 2 if threatened but retreat is pointless
	private int[] lastUpdated; //The turn we last updated the stats for myTiles
	private int[] weighting; // How threatening each robot type is
	
	public Threats(RobotController myrc) {
		rc = myrc;
		enemyHQ = rc.senseEnemyHQLocation();
		enemyTowers = null;
		myTiles = new boolean[Direction.values().length];
		lastUpdated = new int[Direction.values().length];
		weighting = new int[RobotType.values().length];
		for (RobotType t: RobotType.values())
			weighting[t.ordinal()] = unitWeighting(t);
	}
	
	public void update() {		
		enemyTowers = rc.senseEnemyTowerLocations();
		
		if (enemyTowers.length >= 2)
			enemyHQAttackRange = GameConstants.HQ_BUFFED_ATTACK_RADIUS_SQUARED;
		else
			enemyHQAttackRange = RobotType.HQ.attackRadiusSquared;
	}
	
	public boolean inHQRange(MapLocation me) {
		if (enemyTowers.length >= 5) //Account for splash damage
			me = me.add(me.directionTo(enemyHQ));
		
		if (me.distanceSquaredTo(enemyHQ) < enemyHQAttackRange)
			return true;

		return false;
	}
	
	public boolean inTowerRange(MapLocation me) {
		for (MapLocation t: enemyTowers) {
			if (me.distanceSquaredTo(t) < RobotType.TOWER.attackRadiusSquared)
				return true;
		}
		return false;
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
		if (inHQRange(m) || inTowerRange(m)) {
			result = true;
		} else {
			result = false; //Assume it is safe - the code below will set it to threatened
			
			//Check for threat from enemy units
			//We might be able to move in, fire and move out before they act
			double core = rc.getCoreDelay();
			double weapon = rc.getWeaponDelay();
			double supply = rc.getSupplyLevel();
			int turns = 0;
					
			if (!myLoc.equals(m)) { //We need to move here first
				double moveCost = (myLoc.directionTo(m).isDiagonal())?GameConstants.DIAGONAL_DELAY_MULTIPLIER:1;
				turns = (int)core;
				core += myType.movementDelay*moveCost - turns;
			} else { // Consider it safe if we have time to fire here and still move away
				turns = (int)weapon;
				core = Math.max(myType.cooldownDelay, core - turns);
			}
			
			//Move away
			turns += (int)core;
			
			//Work out if we have supply for that many turns
			int suppliedTurns = (int)(supply/myType.supplyUpkeep);
			if (suppliedTurns < turns) //We run out of supply
				turns = 2 * turns - suppliedTurns;
			
			Team enemyTeam = rc.getTeam().opponent();
			for (RobotInfo u: rc.senseNearbyRobots(rc.getType().sensorRadiusSquared)) {
				if (u.type == RobotType.MISSILE) {
					if (myType == RobotType.COMMANDER || (u.team == enemyTeam && myType.movementDelay <= 1)) {
						result = true;
						break;
					}
				}

				if (u.team == enemyTeam && u.type.canAttack() && u.location.distanceSquaredTo(m) <= u.type.attackRadiusSquared) {
					int enemyTurns = Math.max(0, (int)(u.weaponDelay-0.5));
					suppliedTurns = (int)(u.supplyLevel/u.type.supplyUpkeep);
					if (suppliedTurns < enemyTurns)
						enemyTurns = 2 * enemyTurns - suppliedTurns;
					if (enemyTurns <= turns) {
						result = true;
						break;
					}
				}
			}
		}
		
		if (isAdjacent)
			myTiles[d.ordinal()] = result;
		
		return result;		
	}
	
	/*
	 * Work out if we overwhelm the enemy in this area
	 * Each unit type has a weighting based on its damage per round
	 * We sum up all units in the area and multiply the weighting by its hitpoints, dividing by 2 if it has no supply
	 * The area we consider is twice our normal sense range (4*the squared area)
	 * Tiles in that area that we cannot see are considered to have enemy units of average value
	 */
	public boolean overwhelms(MapLocation myLoc) {
		double enemyRating = 0.0;
		double allyRating = 0.0;
		int senseRange = rc.getType().sensorRadiusSquared * 2;
		RobotInfo[] units = rc.senseNearbyRobots(senseRange);
		
		double[] multiplier = { 1.0, 1.25, 1.25, 1.25, 2.0, 2.0, 5.0 }; //The effective Health of the HQ is affected by the number of towers
		
		for (MapLocation t: enemyTowers) {
			if (t.distanceSquaredTo(myLoc) <= senseRange) {
				enemyRating += RobotType.TOWER.maxHealth * weighting[RobotType.TOWER.ordinal()];
			}
		}
		
		if (enemyHQ.distanceSquaredTo(myLoc) <= Math.max(enemyHQAttackRange, senseRange)) {	
			enemyRating += RobotType.HQ.maxHealth * multiplier[enemyTowers.length] * weighting[RobotType.HQ.ordinal()];
		}
		
		//Add in myself - it is not returned in the sense data
		if (rc.getType().canAttack() && rc.getType().canMove())
			allyRating += rc.getHealth() * weighting[rc.getType().ordinal()];
		
		//Add rating from nearby units
		for (RobotInfo u: units) {
			double health = u.health;
			if (u.type.needsSupply() && u.supplyLevel < u.type.supplyUpkeep)
				health /= 2.0;
			if (u.team != rc.getTeam()) {	//enemies				
				if (u.type.canAttack()) {
					enemyRating += health * weighting[u.type.ordinal()];
					if (u.type == RobotType.HQ) // We already added the max HP so take it off now we know the real value 
						enemyRating -= RobotType.HQ.maxHealth * multiplier[enemyTowers.length] * weighting[u.type.ordinal()];
					if (u.type == RobotType.TOWER)
						enemyRating -= RobotType.TOWER.maxHealth * weighting[u.type.ordinal()];
				}
			} else { // allies
				if (u.type.canAttack() && u.type.canMove() && !u.type.canMine())
					allyRating += health * weighting[u.type.ordinal()];
			}
		}
		
		//We need to take into account tiles we cannot sense at the moment
		//We assume they are occupied by enemy units
		/* Too Slow
		for (MapLocation tile: MapLocation.getAllMapLocationsWithinRadiusSq(myLoc, senseRange)) {
			TerrainTile t = map.tile(tile);
			switch(t) {
			case VOID:
				if (!rc.canSenseLocation(tile)) // Assume a drone
					enemyRating += RobotType.DRONE.maxHealth * weighting[(RobotType.DRONE.ordinal()];
				break;
			case UNKNOWN:
			case NORMAL:
				if (!rc.canSenseLocation(tile)) // Assume a tank
					enemyRating += RobotType.TANK.maxHealth * weighting[(RobotType.TANK.ordinal()];
				break;
			case OFF_MAP: //There can be nothing here
				break;
			}
		}
		*/
		rc.setIndicatorString(1, "Turn " + Clock.getRoundNum() + " allies = "+allyRating+" enemy = "+enemyRating);
		
		if (isThreatened(myLoc))
			return (allyRating >= enemyRating && allyRating > 100); //If we are threatened we stand and fight is we could win
		
		//If we are not threatened we only move in with 2:1 ratio in our favour.
		return (allyRating >= enemyRating * 2 && allyRating > 100);
	}
	
	/*
	 * Attack values based on damage per round if we constantly fire
	 */
	private int unitWeighting(RobotType u) {
		switch (u) {
		case BEAVER:
		case MINER:
			return 2;
		case SOLDIER:
		case BASHER:
			return 4;
		case COMMANDER:
			return 10;
		case TANK:
			return 7;
		case DRONE:
			return 3;
		case LAUNCHER:
			return 10;
		case HQ:
			return 12;
		case TOWER:
			return 8;
		}
		return 0;
	}
}
