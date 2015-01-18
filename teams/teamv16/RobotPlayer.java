package teamv16;

import battlecode.common.*;

import java.util.*;

public class RobotPlayer {
	static RobotController rc;
	static Bfs bfs;
	static Team myTeam;
	static Team enemyTeam;
	static int myRange;
	static Random rand;
	static int spawnID;
	static MapLocation enemyHQ;
	static MapLocation[] towers;
	static Direction[] directions = {Direction.NORTH, Direction.NORTH_EAST, Direction.EAST, Direction.SOUTH_EAST, Direction.SOUTH, Direction.SOUTH_WEST, Direction.WEST, Direction.NORTH_WEST};
	static char[] orders = "bMbbbbHmdmddddHddddddSddMddmddmmdddHmdddBmdSmdddmTddmmdtmdTSdtdmtdmtTtmtmttmtttTtmtttTttmttmttTttSmttttmttmttttttSttttttttttStttttttttttttttttttttttttttttttt".toCharArray();
	
	public static void run(RobotController theRC) {
		rc = theRC;
		bfs = new Bfs(rc);
		rand = new Random(rc.getID());

		myRange = rc.getType().attackRadiusSquared;
		myTeam = rc.getTeam();
		enemyTeam = myTeam.opponent();
		enemyHQ = rc.senseEnemyHQLocation();
		
		try {
			spawnID = rc.readBroadcast(65500);
			rc.broadcast(65500, spawnID+1);
			rc.setIndicatorString(0, "Spawn ID "+ spawnID);
		} catch (GameActionException e1) {
			System.out.println("Spawn ID exception");
			e1.printStackTrace();
		}		

		while(true) {	
			towers = rc.senseEnemyTowerLocations();
			
			//Supply units
			double supply = rc.getSupplyLevel();
			if (supply > rc.getType().supplyUpkeep*2) {		
				RobotInfo[] robots = rc.senseNearbyRobots(GameConstants.SUPPLY_TRANSFER_RADIUS_SQUARED, myTeam);			
				//Pass to first neighbour with half the supply we have
				//Never pass back to HQ and always fully empty the HQ
				for (RobotInfo r: robots) {
					if (r.type != RobotType.HQ && r.supplyLevel * 2.0 < supply) {
						try {
							double toTransfer = supply;
							if (rc.getType() != RobotType.HQ)
								toTransfer /= 2.0;
							rc.transferSupplies((int)toTransfer, r.location);
						} catch (GameActionException e) {
							System.out.println("Supply exception");
							e.printStackTrace();
						}
						break;
					}
				}
			}

			//Build or spawn if possible
			if (rc.isCoreReady()) {
				try {
					if (rc.getType().canSpawn()) {
						RobotType build = getBuildOrder();
						if (build != null)
							trySpawn(rc.getLocation().directionTo(enemyHQ), build);							
					} else if (rc.getType().canBuild()) {
						RobotType build = getBuildOrder();
						if (build != null && rc.hasBuildRequirements(build))
							tryBuild(rc.getLocation().directionTo(enemyHQ), build);
					}
				} catch (GameActionException e) {
					System.out.println("Build/Spawn Exception");
					e.printStackTrace();
				}
			}

			//Mine if possible
			if (rc.isCoreReady() && rc.getType().canMine()) {
				double ore = rc.senseOre(rc.getLocation());
				if (ore > 0) {
					//If there is an available adjacent tile with twice as much ore we are better off moving
					Direction startDir = directions[rand.nextInt(directions.length)];
					Direction d = startDir;
					Direction best = Direction.NONE;
					double mostOre = ore;
					boolean done = false;
					while (!done) {
						if (rc.canMove(d)) {
							MapLocation adj = rc.getLocation().add(d);
							if (rc.senseOre(adj) > mostOre) {
								mostOre = rc.senseOre(adj);
								best = d;
							}
						}
						d = d.rotateRight();
						done = (d == startDir);
					}
					try {
						if (best != Direction.NONE && mostOre > ore*2) {						
							tryMove(best);					
						} else {
							rc.mine();
						}
					} catch (GameActionException e) {
						System.out.println("Mining Exception");
						e.printStackTrace();
					}
				}
			}
			
			//Move if we can and want to
			if (rc.isCoreReady() && rc.getType().canMove()) {
				try {
					//If an enemy is in sight move towards the nearest one
					RobotInfo nearest = null;
					RobotInfo[] enemies = rc.senseNearbyRobots(myRange*16, enemyTeam);
					boolean isTank = (rc.getType() == RobotType.TANK);
					for (RobotInfo e: enemies) {
						//TANKS are only used to target towers and HQ
						if (isTank && e.type != RobotType.HQ && e.type != RobotType.TOWER)
							continue;
						if (nearest == null || e.location.distanceSquaredTo(rc.getLocation()) < nearest.location.distanceSquaredTo(rc.getLocation()))
							nearest = e;
					}
					if (nearest != null) {
						tryMove(rc.getLocation().directionTo(nearest.location));
					} else {
						//Odd numbered bots head towards the enemy
						//Even numbered spread out by moving away from the nearest friendly bot
						if (spawnID % 2 == 0 || rc.getType() == RobotType.TANK) {
							Direction lookup = null;
							if (rc.getType() != RobotType.DRONE) // Drones can fly over voids
								lookup = bfs.readResult(rc.getLocation(), enemyHQ);
							if (lookup == null)
								lookup = rc.getLocation().directionTo(enemyHQ);
							tryMove(lookup);
						} else if (Clock.getRoundNum() % 5 == 0 ) {
							tryMove(directions[rand.nextInt(8)]);
						} else {
							RobotInfo[] allies = rc.senseNearbyRobots(rc.getType().sensorRadiusSquared, myTeam);
							for (RobotInfo e: allies) {
								if (e.type.canMove() && (nearest == null ||
										e.location.distanceSquaredTo(rc.getLocation()) < nearest.location.distanceSquaredTo(rc.getLocation())))
									nearest = e;
							}
							if (nearest != null) { // Move away from this unit
								tryMove(nearest.location.directionTo(rc.getLocation()));
							} else { //Move randomly
								tryMove(directions[rand.nextInt(8)]);
							}
						}
					}
				} catch (Exception e) {
					System.out.println("Movement Exception");
					e.printStackTrace();
				}
			}
			
			//Attack if there is an enemy in sight
			if (rc.getType().canAttack() && rc.isWeaponReady()) {
				try {
					attackWeakest();
				} catch (Exception e) {
					System.out.println("Attack Exception");
					e.printStackTrace();
				}
			}
			
			if (rc.getType() == RobotType.HQ && Clock.getRoundNum() > 600 && Clock.getBytecodesLeft() > 1000) {
				try {
					bfs.work(enemyHQ, Bfs.PRIORITY_HIGH, 1000);
				} catch (GameActionException e) {
					System.out.println("Pathfinding exception");
					e.printStackTrace();
				}
			}

			rc.yield();
		}
	}
	
	// Checks to see if building here would block movement
	static boolean wouldBlock(Direction d) {
		RobotInfo[] units = rc.senseNearbyRobots(2, myTeam);
		boolean hasTop = false;
		boolean hasBottom = false;
		boolean hasLeft = false;
		boolean hasRight = false;
		boolean[] blocked = new boolean[8]; //The 8 locations around us
		int clearTiles = 8;
		MapLocation test = rc.getLocation().add(d);
		//Don't build next to a building
		for (RobotInfo r: units)
			if (r.type.canSpawn())
				return true;
		
		test = test.add(Direction.NORTH_WEST);
		if (rc.senseTerrainTile(test).isTraversable() && !buildingAt(test, units)) {
			hasTop = true;
			hasLeft = true;
		} else {
			blocked[0] = true;
			clearTiles--;
		}
		test = test.add(Direction.EAST);
		if (rc.senseTerrainTile(test).isTraversable() && !buildingAt(test, units)) {
			hasTop = true;
		} else {
			blocked[1] = true;
			clearTiles--;
		}
		test = test.add(Direction.EAST);
		if (rc.senseTerrainTile(test).isTraversable() && !buildingAt(test, units)) {
			hasTop = true;
			hasRight = true;
		} else {
			blocked[2] = true;
			clearTiles--;
		}
		test = test.add(Direction.SOUTH);
		if (rc.senseTerrainTile(test).isTraversable() && !buildingAt(test, units)) {
			hasRight = true;
		} else {
			blocked[3] = true;
			clearTiles--;
		}
		test = test.add(Direction.SOUTH);
		if (rc.senseTerrainTile(test).isTraversable() && !buildingAt(test, units)) {
			hasRight = true;
			hasBottom = true;
		} else {
			blocked[4] = true;
			clearTiles--;
		}
		test = test.add(Direction.WEST);
		if (rc.senseTerrainTile(test).isTraversable() && !buildingAt(test, units)) {
			hasBottom = true;
		} else {
			blocked[5] = true;
			clearTiles--;
		}
		test = test.add(Direction.WEST);
		if (rc.senseTerrainTile(test).isTraversable() && !buildingAt(test, units)) {
			hasBottom = true;
			hasLeft = true;
		} else {
			blocked[6] = true;
			clearTiles--;
		}
		test = test.add(Direction.NORTH);
		if (rc.senseTerrainTile(test).isTraversable() && !buildingAt(test, units)) {
			hasLeft = true;
		} else {
			blocked[7] = true;
			clearTiles--;
		}
		
		//Check for a line down the middle
		if (blocked[1] && blocked[5] && hasLeft && hasRight)
			return true;
		//Check for line across
		if (blocked[3] && blocked[7] && hasTop && hasBottom)
			return true;
		//Check for blocked corners
		if (clearTiles > 2) {
			if (!blocked[0] && blocked[1] && blocked[7])
				return true;
			if (!blocked[2] && blocked[1] && blocked[3])
				return true;
			if (!blocked[4] && blocked[3] && blocked[5])
				return true;
			if (!blocked[6] && blocked[5] && blocked[7])
				return true;
		}
		//System.out.println("wouldBlock = false, blocked="+blocked+" hasLeft = "+hasLeft+" hasRight="+hasRight+" hasTop="+hasTop+" hasBootom="+hasBottom);
		return false;
	}
	
	/*
	 * Check to see if there is a building at the given location
	 */
	static boolean buildingAt(MapLocation m, RobotInfo[] units) {
		for (RobotInfo r: units)
			if (!r.type.canMove() && r.location.equals(m))
				return true;
		
		return false;
	}

	/*
	 * We supply a direction to move in
	 * We move there if safe but if not we try the next nearest direction
	 * If we are threatened - we need to move away - if not we stop (stay still) if there is no nearer move
	 */
	private static void tryMove(Direction preferred) throws GameActionException {
		boolean overrideThreat = (Clock.getRoundNum() > 1800);
		MapLocation myLoc = rc.getLocation();
		int senseRange = rc.getType().sensorRadiusSquared*4;	
		RobotInfo[] units = rc.senseNearbyRobots(senseRange); // Look for local enemies (use twice the normal sense radius as other allies might have info		

		if (!overrideThreat) { // We override the threat level if the clock is past 1700 or we have twice the health of his units
			double enemyHealth = 0.0;
			double allyHealth = rc.getHealth();	
			for (MapLocation t: towers) {
				if (t.distanceSquaredTo(myLoc) <= senseRange)
					enemyHealth += RobotType.TOWER.maxHealth;
			}
			
			if (enemyHQ.distanceSquaredTo(myLoc) <= senseRange)
				enemyHealth += RobotType.HQ.maxHealth;
			
			//Add hp from nearby units
			for (RobotInfo u: units) {
				double health = u.health;
				if (u.type.needsSupply() && u.supplyLevel < u.type.supplyUpkeep)
					health /= 2.0;
				if (u.team == enemyTeam) {	//enemies				
					if (u.type.canAttack()) {
						enemyHealth += health;
						if (u.type == RobotType.HQ) // We already added the max HP so take it off now we know the real value 
							enemyHealth -= RobotType.HQ.maxHealth;
						if (u.type == RobotType.TOWER)
							enemyHealth -= RobotType.TOWER.maxHealth;
					}
				} else { // allies
					if (u.type.canAttack() && u.type.canMove())
						allyHealth += health;
				}
			}
			
			overrideThreat = (allyHealth >= enemyHealth * 2.0 && allyHealth > 20);
			rc.setIndicatorString(1, "ally Health = "+allyHealth+" enemy Health = "+enemyHealth);
		}
		
		Direction[] options = { preferred, preferred.rotateRight(), preferred.rotateLeft(), Direction.NONE,
				preferred.rotateLeft().rotateLeft(), preferred.rotateRight().rotateRight(),
				preferred.opposite().rotateRight(), preferred.opposite().rotateLeft(), preferred.opposite() };
		RobotInfo[] enemies = rc.senseNearbyRobots(rc.getType().sensorRadiusSquared, enemyTeam);
		
		rc.setIndicatorString(2, "Safe - preferred = "+preferred+" override = "+overrideThreat);
		for (Direction d: options) {			
			if (d == Direction.NONE || rc.canMove(d)) {
				if (overrideThreat) {
					if (d != Direction.NONE)
						rc.move(d);
					return;
				}
				
				//Calculate if this tile is threatened
				MapLocation tile = myLoc.add(d);
				boolean threatened = false;
				
				if (enemyHQ.distanceSquaredTo(tile) <= RobotType.HQ.attackRadiusSquared)
					threatened = true;
				
				if (!threatened)
					for (MapLocation t: towers) {
						if (t.distanceSquaredTo(tile) <= RobotType.TOWER.attackRadiusSquared)
							threatened = true;
					}
							
				//Check for threat from enemy units
				if (!threatened)
					for (RobotInfo u: enemies) {
						if (u.type.canAttack() && u.location.distanceSquaredTo(tile) <= u.type.attackRadiusSquared) {
							threatened = true;
							break;
						}
					}
				if (!threatened) {
					if (d != Direction.NONE)
						rc.move(d);
					return;
				} else if (d == Direction.NONE)
					rc.setIndicatorString(2, "Threatened");
			}
		}
	}

	//Check to see if we should build
	static RobotType getBuildOrder() {
		int[] buildCount = new int[22];
		int[] robotCount = new int[22];
		RobotInfo[] myRobots = rc.senseNearbyRobots(Integer.MAX_VALUE, myTeam); //Doesn't include me
		int allowSkip = 3;
		
		robotCount[robotTypeToInt(rc.getType())]++;
		//Store the count of each robot type we have		
		for (RobotInfo r : myRobots) {
			robotCount[robotTypeToInt(r.type)]++;
		}

		boolean isBeaver = (rc.getType() == RobotType.BEAVER);
		int now = Clock.getRoundNum();
		//Scan through orders and see what we should build next
		for (char c: orders) {
			RobotType rt = stringToRobotType(c);
			int i = robotTypeToInt(rt);
			if (++buildCount[i] > robotCount[i]) {
				if ((rt.spawnSource == rc.getType() || (isBeaver && rt.spawnSource == null)) && rt.buildTurns + now < GameConstants.ROUND_MAX_LIMIT) {
					return rt;
				}
				if (--allowSkip <= 0)				
					return null;
			}
		}
		return null;
	}
	
	// This method will attack the weakest enemy in sight
	static boolean attackWeakest() throws GameActionException {
		RobotInfo[] targets = rc.senseNearbyRobots(myRange, enemyTeam);
		if (targets.length == 0)
			return false;
		
		// Find enemy with lowest health - choose enemies that can fire first
		RobotInfo weakest = targets[0];
		for (RobotInfo e: targets) {
			if (e.health < weakest.health && (e.type.canAttack() || !weakest.type.canAttack())) {
				weakest = e;
			}
		}

		rc.attackLocation(weakest.location);
		return true;
	}

	// This method will attempt to spawn in the given direction (or as close to it as possible)
	static boolean trySpawn(Direction d, RobotType type) throws GameActionException {
		if (rc.getTeamOre() < type.oreCost)
			return false;
		
		int offsetIndex = 0;
		int[] offsets = {0,1,-1,2,-2,3,-3,4};
		int dirint = directionToInt(d);
		while (offsetIndex < 8 && !rc.canSpawn(directions[(dirint+offsets[offsetIndex]+8)%8], type)) {
			offsetIndex++;
		}
		if (offsetIndex < 8) {
			rc.spawn(directions[(dirint+offsets[offsetIndex]+8)%8], type);
			return true;
		}
		return false;
	}

	// This method will attempt to build in the given direction (or as close to it as possible)
	static boolean tryBuild(Direction d, RobotType type) throws GameActionException {
		if (rc.getTeamOre() < type.oreCost)
			return false;
		int offsetIndex = 0;
		int[] offsets = {0,1,-1,2,-2,3,-3,4};
		int dirint = directionToInt(d);
		while (offsetIndex < 8 && !rc.canMove(directions[(dirint+offsets[offsetIndex]+8)%8])) {
			offsetIndex++;
		}
		if (offsetIndex < 8) {
			rc.build(directions[(dirint+offsets[offsetIndex]+8)%8], type);
			return true;
		}
		return false;
	}

	static int directionToInt(Direction d) {
		switch(d) {
			case NORTH:
				return 0;
			case NORTH_EAST:
				return 1;
			case EAST:
				return 2;
			case SOUTH_EAST:
				return 3;
			case SOUTH:
				return 4;
			case SOUTH_WEST:
				return 5;
			case WEST:
				return 6;
			case NORTH_WEST:
				return 7;
			default:
				return -1;
		}
	}
	
	static int robotTypeToInt(RobotType rt) {
		switch (rt) {
		case HQ: 					return 0;
		case TOWER:					return 1;
		case SUPPLYDEPOT:			return 2;
		case TECHNOLOGYINSTITUTE:	return 3;
		case BARRACKS:				return 4;
		case HELIPAD: 				return 5;
		case TRAININGFIELD: 		return 6;
		case TANKFACTORY: 			return 7;
		case MINERFACTORY: 			return 8;
		case HANDWASHSTATION: 		return 9;
		case AEROSPACELAB: 			return 10;
		case BEAVER: 				return 11;
		case COMPUTER: 				return 12;
		case SOLDIER: 				return 13;
		case BASHER: 				return 14;
		case MINER: 				return 15;
		case DRONE: 				return 16;
		case TANK:					return 17;
		case COMMANDER: 			return 18;
		case LAUNCHER: 				return 19;
		case MISSILE: 				return 20;
		}
		return 21;
	}
	
	static RobotType stringToRobotType(char c) {
		switch (c) {
		case 'S': return RobotType.SUPPLYDEPOT;
		case 'I': return RobotType.TECHNOLOGYINSTITUTE;
		case 'B': return RobotType.BARRACKS;
		case 'H': return RobotType.HELIPAD;
		case 'F': return RobotType.TRAININGFIELD;
		case 'T': return RobotType.TANKFACTORY;
		case 'M': return RobotType.MINERFACTORY;
		case 'W': return RobotType.HANDWASHSTATION;
		case 'A': return RobotType.AEROSPACELAB;
		case 'b': return RobotType.BEAVER;
		case 'c': return RobotType.COMPUTER;
		case 's': return RobotType.SOLDIER;
		case 'a': return RobotType.BASHER;
		case 'm': return RobotType.MINER;
		case 'd': return RobotType.DRONE;
		case 't': return RobotType.TANK;
		case 'o': return RobotType.COMMANDER;
		case 'l': return RobotType.LAUNCHER;
		}
		
		return RobotType.HQ;
	}
}
