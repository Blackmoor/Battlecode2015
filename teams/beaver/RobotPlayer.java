package beaver;

import battlecode.common.*;

import java.util.*;

public class RobotPlayer {
	static RobotController rc;
	static Team myTeam;
	static Team enemyTeam;
	static int myRange;
	static Random rand;
	static Direction[] directions = {Direction.NORTH, Direction.NORTH_EAST, Direction.EAST, Direction.SOUTH_EAST, Direction.SOUTH, Direction.SOUTH_WEST, Direction.WEST, Direction.NORTH_WEST};
	//static char[] orders = "bbbbbHbbdbHdddddddddSddMddmddmmdddHmdddBmdmdddmddmMmdTHddmdSdtdmdmdtddmddTttmttddttttmtttddTttmtdtdmttdtdtdSmtdttdtmtdtmtdtdtttdtSttdttttdttttStt".toCharArray();
	static char[] orders = "bMmmBmmsmMsmsTmsmsmtmmstTmsttmsttTmstttmsttmttmtTttmtttmttttmtStttmttttSttttmttttttmStttttmttttSttttmttttSttttttttStttttttttttttttttttttttttt".toCharArray();
	
	
	public static void run(RobotController tomatojuice) {
		rc = tomatojuice;
		rand = new Random(rc.getID());

		myRange = rc.getType().attackRadiusSquared;
		myTeam = rc.getTeam();
		enemyTeam = myTeam.opponent();


		while(true) {			
			//Supply units - move what we don't need to next Highest ID robot. If there is none higher then we move it to the lowest ID ready for next turn
			double toTransfer = rc.getSupplyLevel() - rc.getType().supplyUpkeep * 100.0; //Keep 100 turns worth of supply
			if (toTransfer > 0) { //We have enough to pass some on				
				RobotInfo[] robots = rc.senseNearbyRobots(GameConstants.SUPPLY_TRANSFER_RADIUS_SQUARED, myTeam);
				int lowestID = rc.getID();
				MapLocation lowestLoc = null;
				int nextID = 9999999;
				MapLocation nextLoc = null;
				
				for (RobotInfo r: robots) {
					if (r.ID < lowestID) {
						lowestID = r.ID;
						lowestLoc = r.location;
					} else if (r.ID > rc.getID() && r.ID < nextID) {
						nextID = r.ID;
						nextLoc = r.location;
					}
				}
				// Now do transfers
				try {
					if (nextLoc != null) {
						rc.transferSupplies((int)toTransfer, nextLoc);
					} else if (lowestLoc != null) {
						rc.transferSupplies((int)toTransfer, lowestLoc);
					}
				} catch (GameActionException e) {
					System.out.println("Supply exception");
					e.printStackTrace();
				}
			}

			//Attack if safe to do so - TODO - check current location is safe
			if (rc.getType().canAttack() && rc.isWeaponReady()) {
				try {
					attackSomething();
				} catch (Exception e) {
					System.out.println("Attack Exception");
					e.printStackTrace();
				}
			}

			//Build or spawn if possible
			if (rc.isCoreReady()) {
				try {
					if (rc.getType().canSpawn()) {
						RobotType build = getBuildOrder();
						if (build != null)
							trySpawn(directions[rand.nextInt(8)], build);							
					} else if (rc.getType().canBuild()) {
						RobotType build = getBuildOrder();
						if (build != null)
							tryBuild(directions[rand.nextInt(8)], build);
					}
				} catch (GameActionException e) {
					System.out.println("Build/Spawn Exception");
					e.printStackTrace();
				}
			}

			//Mine if possible
			if (rc.isCoreReady() && rc.getType().canMine()) {
				if (rc.senseOre(rc.getLocation()) > 0)
					try {
						rc.mine();
					} catch (GameActionException e) {
						System.out.println("Mining Exception");
						e.printStackTrace();
					}
			}
			
			//Move if possible
			if (rc.isCoreReady() && rc.getType().canMove()) {
				try {
					int fate = rand.nextInt(1000);
					if (fate < 800 && rc.getType() != RobotType.TANK) {
						tryMove(directions[rand.nextInt(8)]);
					} else {
						tryMove(rc.getLocation().directionTo(rc.senseEnemyHQLocation()));
					}
				} catch (Exception e) {
					System.out.println("Movement Exception");
					e.printStackTrace();
				}
			}

			rc.yield();
		}
	}

	//Check to see if we should build
	static RobotType getBuildOrder() {
		int toSkip = 2; // We can skip a few orders before giving up
		int[] buildCount = new int[22];
		int[] robotCount = new int[22];
		RobotInfo[] myRobots = rc.senseNearbyRobots(999999, myTeam);
		
		//Store the count of each robot type we have		
		for (RobotInfo r : myRobots) {
			robotCount[robotTypeToInt(r.type)]++;
		}

		boolean isBeaver = rc.getType() == RobotType.BEAVER;
		//Scan through orders and see what we should build next
		for (char c: orders) {
			RobotType rt = stringToRobotType(c);
			int i = robotTypeToInt(rt);
			if (++buildCount[i] > robotCount[i]) {
				if (rt.spawnSource == rc.getType() || (isBeaver && rt.spawnSource == null)) {
					return rt;
				}
				if (--toSkip <= 0) {
					return null;
				}
			}
		}
		return null;
	}
	
	// This method will attack an enemy in sight, if there is one
	static boolean attackSomething() throws GameActionException {
		RobotInfo[] enemies = rc.senseNearbyRobots(myRange, enemyTeam);
		if (enemies.length > 0) {
			// Attack enemy with lowest health
			double lowestHP = 999999;
			MapLocation lowestLoc = null;
			for (RobotInfo e: enemies) {
				if (e.health < lowestHP) {
					lowestHP = e.health;
					lowestLoc = e.location;
				}
			}
			rc.attackLocation(lowestLoc);
			return true;
		}
		return false;
	}

	// This method will attempt to move in Direction d (or as close to it as possible)
	// It will avoid areas that are threatened by enemies with a higher attack range than us
	static void tryMove(Direction d) throws GameActionException {
		int offsetIndex = 0;
		int[] offsets = {0,1,-1,2,-2,3,-3,4};
		int dirint = directionToInt(d);
		while (offsetIndex < 8 && !canMoveSafely(directions[(dirint+offsets[offsetIndex]+8)%8])) {
			offsetIndex++;
		}
		if (offsetIndex < 8) {
			rc.move(directions[(dirint+offsets[offsetIndex]+8)%8]);
		}
	}
	
	static boolean canMoveSafely(Direction d) {
		if (!rc.canMove(d))
			return false;
		
		if (Clock.getRoundNum() > 1800 || (Clock.getRoundNum() > 1500 && rc.getType() == RobotType.TANK)) {
			return true;
		}
		
		MapLocation loc = rc.getLocation().add(d);
		//Check to see if any enemies can target the location before we can move away
		
		for (RobotInfo r: rc.senseNearbyRobots(rc.getType().sensorRadiusSquared, enemyTeam)) {
			if (loc.distanceSquaredTo(r.location) <= r.type.attackRadiusSquared)
				return false;
		}
		
		MapLocation[] towers = rc.senseEnemyTowerLocations();
		for (MapLocation t: towers)
			if (loc.distanceSquaredTo(t) <= RobotType.TOWER.attackRadiusSquared)
				return false;
		
		if (loc.distanceSquaredTo(rc.senseEnemyHQLocation()) <= RobotType.HQ.attackRadiusSquared)
			return false;
				
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
