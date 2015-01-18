package team367;

import battlecode.common.*;

import java.util.*;

public class RobotPlayer {
	static RobotController rc;
	static Bfs bfs; //A background breadth first search class for units that walk on the ground
	static BuildStrategy strategy; //Used to determine the next build order
	static Threats threats; //Stored the tiles threatened by the enemy towers and HQ
	static Sensors sensors;
	static Team myTeam;
	static Team enemyTeam;
	static RobotType myType;
	static MapLocation myHQ;
	static Random rand;
	static int spawnID;
	static MapLocation myLoc;
	static Direction[] directions = {Direction.NORTH, Direction.NORTH_EAST, Direction.EAST, Direction.SOUTH_EAST, Direction.SOUTH, Direction.SOUTH_WEST, Direction.WEST, Direction.NORTH_WEST};
	
	public static void run(RobotController theRC) {
		rc = theRC;

		//These don't change so get them once and use the local variable (performance)
		myType = rc.getType();
		myTeam = rc.getTeam();
		enemyTeam = myTeam.opponent();
		myHQ = rc.senseHQLocation();
		myLoc = rc.getLocation();
		
		if (myType == RobotType.MISSILE)
			runMissile();
		
		try {
			spawnID = rc.readBroadcast(65500);
			rc.broadcast(65500, spawnID+1);
			rc.setIndicatorString(0, "Spawn ID "+ spawnID);
		} catch (GameActionException e) {
			System.out.println("Spawn ID exception");
			//e.printStackTrace();
		}
		
		if (myType.canMove())
			bfs = new Bfs(rc); // We need to check the breadth first search results to move optimally
		sensors = new Sensors(rc); // All these units need to sense enemies to determine if a tile is safe
		threats = new Threats(rc, sensors);
		
		if (myType == RobotType.HQ)
			runHQ();
		else if (myType == RobotType.TOWER)
			runTower();
		else if (myType.isBuilding)
			runBuilding();
		else if (myType.canBuild())
			runBeaver();
		else if (myType.canMine())
			runMiner(); // Includes Beavers
		else if (myType.canAttack() || myType == RobotType.LAUNCHER)
			runCombat();
		else
			runOther();
	}
	
	/*
	 * The run functions are not supposed to return
	 * They run a while true loop, calling yield at the end of each turns processing
	 */
	
	//HQ is responsible for collating unit counts and broadcasting them each turn
	//It also needs to pass on its supply each turn and fire if there are enemies in range
	private static void runHQ() {
		strategy = new BuildStrategy(rc);
		
		while(true) {
			threats.update();
			strategy.broadcast();
			
			// See if we need to spawn a beaver
			if (rc.isCoreReady()) {
				RobotType build = strategy.getBuildOrder();
				if (build != null)				
					trySpawn(rc.getLocation().directionTo(threats.enemyHQ), build);							
			}
			
			//Attack if there is an enemy in sight
			if (rc.isWeaponReady())
				attackWeakest();
			
			doTransfer();
			
			rc.yield();
		}
	}
	
	private static void runTower() {
		while(true) {
			threats.update();
			
			//Attack if there is an enemy in sight
			if (rc.isWeaponReady())
				attackWeakest();
			
			doTransfer();
			
			rc.yield();
		}
	}
	
	// Factories and supply depots 
	private static void runBuilding() { //Most builds spawn units
		if (myType.canSpawn())
			strategy = new BuildStrategy(rc);
		
		while(true) {			
			if (rc.isCoreReady() && myType.canSpawn()) {
				threats.update();
				RobotType build = strategy.getBuildOrder();
				if (build != null)
					trySpawn(rc.getLocation().directionTo(threats.enemyHQ), build);							
			}
			
			doTransfer();
			
			rc.yield();
		}
	}
	
	// Miners
	private static void runMiner() {
		rand = new Random(rc.getID());
		
		while(true) {
			threats.update();
			myLoc = rc.getLocation();
			double ore = rc.senseOre(rc.getLocation());
	
			//Move if we can and want to
			if (rc.isCoreReady() && myType.canMove()) {
				boolean ignoreThreat = overwhelms();
				
				if (!ignoreThreat && shouldRetreat())
					doRetreatMove(); //Pull back if in range of the enemy guns
				else
					doMinerMove();
				if (ore == 0) { 
					boolean engaged = false;
					if (rc.isCoreReady() && inCombat()) // A miner without ore acts as a weak combat unit
						engaged = doCloseWithEnemyMove(ignoreThreat);
					if (rc.isCoreReady() && !engaged) //Close with enemy might not do a move if the enemy is a drone out of reach
						doAdvanceMove();
				}
			}
			
			//Attack if there is an enemy in sight
			if (rc.isWeaponReady())
				attackWeakest();
			
			//Mine if possible
			if (rc.isCoreReady() && ore > 0) {
				try {
					rc.mine();
				} catch (GameActionException e) {
					System.out.println("Mining Exception");
					//e.printStackTrace();
				}
			}
			
			doTransfer();
			
			rc.yield();
		}
	}
	
	// Beavers
	private static void runBeaver() {
		strategy = new BuildStrategy(rc);
		rand = new Random(rc.getID());
		
		while(true) {
			threats.update();
			myLoc = rc.getLocation();
			double ore = rc.senseOre(rc.getLocation());
			
			if (rc.isCoreReady()) {
				RobotType build = strategy.getBuildOrder();
				if (build != null)
					tryBuild(rc.getLocation().directionTo(threats.enemyHQ), build);							
			}
			
			//Move if we can and want to
			if (rc.isCoreReady()) {
				boolean ignoreThreat = overwhelms();
				
				if (!ignoreThreat && shouldRetreat())
					doRetreatMove(); //Pull back if in range of the enemy guns
				else
					doMinerMove();
				if (ore == 0) { // A Beaver without ore acts as a weak combat unit
					boolean engaged = false;
					if (rc.isCoreReady() && inCombat()) // A miner without ore acts as a weak combat unit
						engaged = doCloseWithEnemyMove(ignoreThreat);
					if (rc.isCoreReady() && !engaged) //Close with enemy might not do a move if the enemy is a drone out of reach
						doAdvanceMove();
				}
			}
			
			//Attack if there is an enemy in sight
			if (rc.isWeaponReady())
				attackWeakest();
			
			//Mine if possible
			if (rc.isCoreReady() && ore > 0) {
				try {
					rc.mine();
				} catch (GameActionException e) {
					System.out.println("Mining Exception");
					//e.printStackTrace();
				}
			}
			
			doTransfer();
			
			rc.yield();
		}
	}
	
	// All combat units (Soldiers, Bashers, Tanks, Drones, Launchers, Commander)
	private static void runCombat() {
		while(true) {
			threats.update();
			myLoc = rc.getLocation();
			
			//Move if we can and want to
			if (rc.isCoreReady()) {
				boolean ignoreThreat = overwhelms();
				
				if (!ignoreThreat && shouldRetreat()) {
					if (rc.isWeaponReady() && myType.loadingDelay == 0)
						attackWeakest();
					doRetreatMove(); //Pull back if in range of the enemy guns
				} else {
					boolean engaged = false;
					if (rc.isCoreReady() && inCombat())
						engaged = doCloseWithEnemyMove(ignoreThreat);
					if (rc.isCoreReady() && !engaged) //Close with enemy might not do a move if the enemy is a drone out of reach
						doAdvanceMove();
				}
			}
			
			//Attack if there is an enemy in sight
			if (myType == RobotType.LAUNCHER)
				doLaunch();
			else if (rc.isWeaponReady())
				attackWeakest();
			
			doTransfer();
			
			rc.yield();
		}
	}
	
	// Computers
	private static void runOther() {
		while(true) {
			threats.update();
			myLoc = rc.getLocation();
						
			//Move if we can and want to
			if (rc.isCoreReady() && myType.canMove()) {				
				if (shouldRetreat())
					doRetreatMove(); //Pull back if in range of the enemy guns
				else
					doAdvanceMove(); // Move towards our HQ
			}
		
			doTransfer();
			
			//Perform a background breadth first search to the enemy HQ
			if (myType == RobotType.COMPUTER && Clock.getBytecodesLeft() > 1000) {
				try {
					bfs.work(threats.enemyHQ, Bfs.PRIORITY_HIGH, 500);
				} catch (GameActionException e) {
					System.out.println("Pathfinding exception");
					//e.printStackTrace();
				}
			}

			rc.yield();
		}
	}
	
	private static void doLaunch() {
		int count = rc.getMissileCount();
		if (count > 0) {
			int missileRange = (2+GameConstants.MISSILE_LIFESPAN)*(2+GameConstants.MISSILE_LIFESPAN);
			RobotInfo[] enemies = rc.senseNearbyRobots(missileRange, enemyTeam);
			MapLocation target = null;
			if (enemies.length > 0)
				target = enemies[0].location;
			else { //Check for towers or HQ
				for (MapLocation t: threats.enemyTowers) {
					if (t.distanceSquaredTo(myLoc) <= missileRange) {
						target = t;
						break;
					}
				}
				
				if (target == null && threats.enemyHQ.distanceSquaredTo(myLoc) <= missileRange)
					target = threats.enemyHQ;
			}
			if (target != null) {
				try {
					Direction d = myLoc.directionTo(target);
					
					if (rc.canLaunch(d)) {	
						rc.launchMissile(d);
						count--;
					}
					if (count > 0 && rc.canLaunch(d.rotateRight())) {
						rc.launchMissile(d.rotateRight());
						count--;
					}
					if (count > 0 && rc.canLaunch(d.rotateLeft())) {
						rc.launchMissile(d.rotateLeft());
						count--;
					}
				} catch (GameActionException e) {
					System.out.println("Launch exception");
					e.printStackTrace();
				}
			}
		}
	}
	
	private static void doTransfer() {
		//Supply units
		if (Clock.getBytecodesLeft() > 850) { //Transfer costs 500, sense costs 100 and we need some processing time
			double supply = rc.getSupplyLevel();
			double supplyToKeep = myType.supplyUpkeep*10; // 10 turns worth of supply
			if (myType == RobotType.COMMANDER)
				supplyToKeep = (GameConstants.ROUND_MAX_LIMIT - Clock.getRoundNum()) * myType.supplyUpkeep; // Keep enough to last to the end of the game
			if (supply > supplyToKeep) {		
				RobotInfo[] robots = rc.senseNearbyRobots(GameConstants.SUPPLY_TRANSFER_RADIUS_SQUARED, myTeam);			
				//Pass to first neighbour with half the supply we have
				//Never pass to buildings and always fully empty the HQ
				for (RobotInfo r: robots) {
					if (!r.type.isBuilding && r.supplyLevel * 2.0 < supply) {
						double toTransfer = supply;
						if (myType != RobotType.HQ)
							toTransfer /= 2.0;
						try {							
							rc.transferSupplies((int)toTransfer, r.location);
						} catch (GameActionException e) {
							System.out.println("Supply exception");
							//e.printStackTrace();
						}
						break;
					}
				}
			}
		}
	}
	
	/*
	 * MOVEMENT code
	 * 
	 * These functions determine how best to move
	 * 
	 * A unit is threatened if it is in combat range of an enemy unit
	 * This state can be overridden if we out gun the opponent or we are near the end of the game and need to attack at will
	 * 
	 * A unit is in combat if it can sense enemies nearby - default behaviour is to advance on the weakest enemy
	 */
	
	private static boolean overwhelms() {
		return (GameConstants.ROUND_MAX_LIMIT - Clock.getRoundNum() < 200 || threats.overwhelms(myLoc));
	}
	
	// If our tile is threatened we should retreat unless the enemy is quicker than us
	// If the enemy can advance and fire before we can move away we might as well stand and fight
	private static boolean shouldRetreat() {
		return threats.isThreatened(myLoc);
	}
	
	private static boolean inCombat() {
		return sensors.enemies(SensorRange.EXTREME).length > 0 || threats.isThreatened(myLoc);
	}
	
	private static void tryMove(Direction preferred, boolean ignoreThreat) {
		Direction[] options = null;		
		int turn = Clock.getRoundNum();
		if ((turn/50)%3 == 0) { // 1/3 of the time we prefer to move left
			Direction[]allMovesLeft = { preferred, preferred.rotateLeft(), preferred.rotateRight(), 
					preferred.rotateLeft().rotateLeft(), preferred.rotateRight().rotateRight(), Direction.NONE,
					preferred.opposite().rotateRight(), preferred.opposite().rotateLeft(), preferred.opposite() };
			options = allMovesLeft;
		} else if ((turn/50)%3 == 1) { // 1/3 of the time we prefer to move right
			Direction[]allMovesRight = { preferred, preferred.rotateRight(), preferred.rotateLeft(), 
					preferred.rotateRight().rotateRight(), preferred.rotateLeft().rotateLeft(), Direction.NONE,
					preferred.opposite().rotateLeft(), preferred.opposite().rotateRight(), preferred.opposite() };
			options = allMovesRight;
		} else { //1/3 of the time we prefer to stay still if we can't move forward
			Direction[]allMovesForward = { preferred, preferred.rotateLeft(), preferred.rotateRight(), Direction.NONE,
					preferred.rotateLeft().rotateLeft(), preferred.rotateRight().rotateRight(), 
					preferred.opposite().rotateRight(), preferred.opposite().rotateLeft(), preferred.opposite() };
			options = allMovesForward;
		}
		
		//Scan through possible moves
		//If we find a valid move but it is a threatened tile we store it and continue
		//If we don't find a better move we use the stored one
		Direction retreat = null;
		
		for (Direction d: options) {
			boolean valid = (d == Direction.NONE || rc.canMove(d));
			MapLocation dest = myLoc.add(d);
			if (valid) {	// This is a valid move - check to see if it is safe				
				if (ignoreThreat || !threats.isThreatened(dest)) { //Do this move
					if (d != Direction.NONE)
						try {
							//System.out.println("tryMove: preferred " + preferred + " got " + d);
							rc.move(d);
						} catch (GameActionException e) {
							System.out.println("Movement exception");
							//e.printStackTrace();
						}
					return;
				} else if (retreat == null)
					retreat = d;
			}
		}
		
		if (retreat != null && retreat != Direction.NONE) {
			try {
				rc.move(retreat);
			} catch (GameActionException e) {
				System.out.println("Movement exception");
				//e.printStackTrace();
			}
		}
	}
	
	private static RobotInfo nearestUnit(RobotInfo[] units) {
		if (units.length == 0)
			return null;
		
		RobotInfo nearest = units[0];
		for (RobotInfo r: units) {
			if (myLoc.distanceSquaredTo(r.location) < myLoc.distanceSquaredTo(nearest.location))
				nearest = r;
		}
		return nearest;
	}
	
	/*
	 * If there are no good moves we should stay and fight
	 */
	private static void doRetreatMove() {		
		try {
			if (myType == RobotType.COMMANDER && rc.hasLearnedSkill(CommanderSkillType.FLASH) && rc.getFlashCooldown() == 0)
				flashTowards(myHQ, true);
			Direction d = myLoc.directionTo(myHQ);
			rc.setIndicatorString(2, "Threatened - retreating towards HQ " + d);
			if (rc.isCoreReady())
				tryMove(d, false);
		} catch (GameActionException e) {
			System.out.println("Retreat exception");
			//e.printStackTrace();
		}					
	}
	
	private static void flashTowards(MapLocation m, boolean ignoreThreat) {
		//We want to pick a safe tile that is within flash range (10) and nearest to the destination
		//If we are allowed to ignore threat store the nearest threatened tile in case there are no safe ones
		MapLocation[] inRange = MapLocation.getAllMapLocationsWithinRadiusSq(myLoc, GameConstants.FLASH_RANGE_SQUARED);
		MapLocation bestSafe = myLoc;
		MapLocation best = myLoc; // The closest regardless of threat
		
		try {
			for (MapLocation target: inRange) {
				if (!target.equals(myLoc) && rc.isPathable(myType, target) && !rc.isLocationOccupied(target) ) {
					if (target.distanceSquaredTo(m) < bestSafe.distanceSquaredTo(m) && !threats.isThreatened(target))
						bestSafe = target;
					if (target.distanceSquaredTo(m) < best.distanceSquaredTo(m))
						best = target;
				}
			}
			
			if (!bestSafe.equals(myLoc))
				rc.castFlash(bestSafe);
			else if (ignoreThreat && !best.equals(myLoc))
				rc.castFlash(best);
		} catch (GameActionException e) {
			System.out.println("Flash exception");
			//e.printStackTrace();
		}
	}
	
	private static void doMinerMove() {
		double ore = rc.senseOre(myLoc);

		//If there is an available adjacent tile with twice as much ore we are better off moving
		Direction startDir = directions[rand.nextInt(directions.length)];
		Direction d = startDir;
		Direction best = Direction.NONE;
		double mostOre = ore;
		boolean done = false;
		while (!done) {
			if (rc.canMove(d)) {
				MapLocation adj = rc.getLocation().add(d);
				if (!threats.isThreatened(adj) && rc.senseOre(adj) > mostOre) {
					mostOre = rc.senseOre(adj);
					best = d;
				}
			}
			d = d.rotateRight();
			done = (d == startDir);
		}
		if (best != Direction.NONE && mostOre > ore*2) {
			rc.setIndicatorString(2, "Mining - better ore " + d);
			try {
				rc.move(best);
			} catch (GameActionException e) {
				System.out.println("Miner move exception");
				//e.printStackTrace();
			}					
		}
	}

	// Move towards to enemy HQ if we can attack, otherwise head to home HQ
	private static void doAdvanceMove() {
		try {
			if (myType == RobotType.COMMANDER && rc.hasLearnedSkill(CommanderSkillType.FLASH) && rc.getFlashCooldown() == 0)
				flashTowards(threats.enemyHQ, false);
		} catch (GameActionException e) {
			System.out.println("Flash exception");
			//e.printStackTrace();
		}			

		if (rc.isCoreReady()) {
			Direction dir = null;
			
			if (myType.canAttack()) {			
				if (myType != RobotType.DRONE)
					dir = bfs.readResult(myLoc, threats.enemyHQ);
				if (dir == null)
					dir = myLoc.directionTo(threats.enemyHQ);
				rc.setIndicatorString(2, "Advancing " + dir);
			} else {
				dir = myLoc.directionTo(myHQ);
				rc.setIndicatorString(2, "HQ Defensive unit " + dir);
			}
			tryMove(dir, false);
		}
	}
	
	private static boolean doCloseWithEnemyMove(boolean ignoreThreat) {
		//Move to within attack range of the nearest enemy - ignore towers and HQ until later in the game
		//We can move in closer if we are still out of range of the enemy
		RobotInfo nearest = null;
		RobotInfo[] enemies = sensors.enemies(SensorRange.EXTREME);
		boolean canFly = (myType == RobotType.DRONE);
		
		int now = Clock.getRoundNum();
		for (RobotInfo e: enemies) {
			//We want to find and circle units, not towers or HQ (until later in the game)
			//Drone on VOID tiles cannot be reached by ground troops - ignore them
			if (now < GameConstants.ROUND_MAX_LIMIT/2 && (e.type == RobotType.HQ || e.type == RobotType.TOWER))
				continue;
			if (!canFly && rc.senseTerrainTile(e.location) != TerrainTile.NORMAL)
				continue;
			if (nearest == null || e.location.distanceSquaredTo(rc.getLocation()) < nearest.location.distanceSquaredTo(rc.getLocation()))
				nearest = e;
		}
		if (nearest != null) {
			if (ignoreThreat || myLoc.distanceSquaredTo(nearest.location) > myType.attackRadiusSquared) {
				rc.setIndicatorString(2, "Closing with " + nearest.type + " at " + nearest.location);
				try {
					if (myType == RobotType.COMMANDER && rc.hasLearnedSkill(CommanderSkillType.FLASH) && rc.getFlashCooldown() == 0)
						flashTowards(threats.enemyHQ, false);
				} catch (GameActionException e) {
					System.out.println("Flash exception");
					//e.printStackTrace();
				}			
				if (rc.isCoreReady())
					tryMove(rc.getLocation().directionTo(nearest.location), ignoreThreat);
			} else {
				rc.setIndicatorString(2, "Holding at range with " + nearest.type + " at " + nearest.location);
			}
			return true;
		}
		return false;
	}
	
	private static void runMissile() {
		int turns = GameConstants.MISSILE_LIFESPAN;
		MapLocation enemyHQ = rc.senseEnemyHQLocation();
		
		while (true) {
			myLoc = rc.getLocation();			
			int moveRange = (1+turns)*(1+turns);
			RobotInfo[] inRange = rc.senseNearbyRobots(moveRange);
			RobotInfo nearestEnemy = null;
			RobotInfo nearestAlly = null;
			for (RobotInfo u: inRange) {
				if (u.team == myTeam) {
					if (nearestAlly == null || myLoc.distanceSquaredTo(u.location) < myLoc.distanceSquaredTo(nearestAlly.location))
						nearestAlly = u;
				} else {
					if (nearestEnemy == null || myLoc.distanceSquaredTo(u.location) < myLoc.distanceSquaredTo(nearestEnemy.location))
						nearestEnemy = u;
				}
			}
			
			MapLocation target = null;
			if (nearestEnemy == null) { // check for towers and HQ as they might be out of sensor range
				MapLocation[] enemyTowers = rc.senseEnemyTowerLocations();
				for (MapLocation t: enemyTowers) {
					if (myLoc.distanceSquaredTo(t) <= moveRange) {
						target = t;
						break;
					}
				}
				
				if (target == null && myLoc.distanceSquaredTo(enemyHQ) <= moveRange) {
					target = enemyHQ;
				}
			} else {
				target = nearestEnemy.location;
			}
			
			try {
				if (target != null) {
					if (myLoc.distanceSquaredTo(target) <= GameConstants.MISSILE_RADIUS_SQUARED)
						rc.explode();
					else {
						Direction d = myLoc.directionTo(target);
						if (rc.canMove(d))
							rc.move(d);
						else if (rc.canMove(d.rotateLeft()))
							rc.move(d.rotateLeft());
						else if (rc.canMove(d.rotateRight()))
							rc.move(d.rotateRight());
					}
				} else if (nearestAlly != null) {
					Direction d = myLoc.directionTo(nearestAlly.location).opposite();
					if (rc.canMove(d))
						rc.move(d);
					else if (rc.canMove(d.rotateLeft()))
						rc.move(d.rotateLeft());
					else if (rc.canMove(d.rotateRight()))
						rc.move(d.rotateRight());
				}
			} catch (GameActionException e) {
				System.out.println("Missile exception");
				//e.printStackTrace();
			}
			turns--;
			rc.yield();
		}
	}
	
	// Checks to see if building here would block movement
	static boolean wouldBlock(Direction d) {
		MapLocation target = myLoc.add(d);
		if ((target.x + target.y) % 2 != (myHQ.x + myHQ.y) % 2) // A quick check to ensure we are lined up with the HQ - this will create a chequerboard pattern
			return true;
		
		RobotInfo[] units = rc.senseNearbyRobots(2, myTeam);
		boolean hasTop = false;
		boolean hasBottom = false;
		boolean hasLeft = false;
		boolean hasRight = false;
		boolean[] blocked = new boolean[8]; //The 8 locations around us
		int clearTiles = 8;
		MapLocation test = rc.getLocation().add(d);
		
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
	
	// Check to see if there is a building at the given location
	// i.e. we find a unit that cannot move here
	private static boolean buildingAt(MapLocation m, RobotInfo[] units) {
		for (RobotInfo r: units)
			if (!r.type.canMove() && r.location.equals(m))
				return true;
		
		return false;
	}
	
	
	// This method will attack the weakest enemy in sight
	static boolean attackWeakest() {
		int range = myType.attackRadiusSquared;
		if (myType == RobotType.HQ && rc.senseTowerLocations().length >= 2)
			range = GameConstants.HQ_BUFFED_ATTACK_RADIUS_SQUARED;
		RobotInfo[] targets = rc.senseNearbyRobots(range, enemyTeam);
		if (targets.length == 0)
			return false;
		
		// Find enemy with lowest health - choose enemies that can fire first
		RobotInfo weakest = targets[0];
		for (RobotInfo e: targets) {
			if ((!weakest.type.canAttack() && e.type.canAttack()) ||
					(weakest.type.canAttack() == e.type.canAttack() && e.health < weakest.health)) {
				weakest = e;
			}
		}

		try {
			rc.attackLocation(weakest.location);
		} catch (GameActionException e1) {
			System.out.println("Attack exception");
			//e.printStackTrace();
		}
		return true;
	}

	// This method will attempt to spawn in the given direction (or as close to it as possible)
	static boolean trySpawn(Direction d, RobotType type) {
		if (!rc.hasSpawnRequirements(type))
			return false;
		
		int offsetIndex = 0;
		int[] offsets = {0,1,-1,2,-2,3,-3,4};
		int dirint = directionToInt(d);
		while (offsetIndex < 8) {
			int i = (dirint+offsets[offsetIndex]+8)%8;
			Direction spawn = directions[i];
			MapLocation m = myLoc.add(directions[i]);
			if (rc.canSpawn(spawn, type) && rc.hasSpawnRequirements(type) && !threats.isThreatened(m)) {
				try {
					rc.spawn(spawn, type);
				} catch (GameActionException e) {
					System.out.println("Spawn exception");
					//e.printStackTrace();
				}
				return true;
			}
			offsetIndex++;
		}

		return false;
	}

	// This method will attempt to build in the given direction (or as close to it as possible)
	static boolean tryBuild(Direction d, RobotType type) {
		int offsetIndex = 0;
		int[] offsets = {0,1,-1,2,-2,3,-3,4};
		int dirint = directionToInt(d);
		while (offsetIndex < 8) {
			int i = (dirint+offsets[offsetIndex]+8)%8;
			Direction build = directions[i];
			MapLocation m = myLoc.add(build);
			
			if (rc.canBuild(build, type) &&
					!wouldBlock(build) &&
					!threats.isThreatened(m)) {
				try {
					rc.build(directions[i], type);
				} catch (GameActionException e) {
					System.out.println("Build exception");
					//e.printStackTrace();
				}
				return true;
			}
			offsetIndex++;
		}

		return false;
	}

	static private int directionToInt(Direction d) {
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
}
