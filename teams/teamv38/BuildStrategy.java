package teamv38;

import battlecode.common.*;

/*
 * Used to record the number of each unit type in the broadcast area
 * The HQ uses the store function and all factories/beavers use the read function
 */
public class BuildStrategy {
	private RobotController rc;
	private int[] unitCounts;
	private boolean[] isIdle; //Set to true if a building of this type is not actively building
	private int requiredTowers; // The number of supply towers we need to support the units
	private int requiredMiners; // The number of miners we need to support all the factories and beavers we have
	private int maxRounds; // The turn on which the game will end
	
	public BuildStrategy(RobotController myrc) {
		rc = myrc;
		requiredTowers = 0;
		requiredMiners = 0;
		maxRounds = GameConstants.ROUND_MAX_LIMIT;
	}
	
	public void broadcast() {
		RobotInfo[] myRobots = rc.senseNearbyRobots(Integer.MAX_VALUE, rc.getTeam());
		int supportCost = 0;
		int factories = 0;
		unitCounts = new int[22];
		isIdle = new boolean[22];
		
		//Store the count of each robot type we have and if it is idle	
		for (RobotInfo r : myRobots) {
			unitCounts[r.type.ordinal()]++;
			supportCost += r.type.supplyUpkeep;
			if (r.type.canSpawn()) {
				factories++;
				if (r.coreDelay < 1)
					isIdle[r.type.ordinal()] = true;
			}
		}
		
		try {
			for (RobotType t: RobotType.values()) {
				int i = t.ordinal();
				int data = unitCounts[i] << 1;
				if (isIdle[i])
					data |= 1;
				rc.broadcast(50000+i, data);
			}
			requiredTowers = 0;
			if (supportCost > 200)
				requiredTowers = (int)Math.ceil(Math.pow(supportCost/100-2, 1.0/GameConstants.SUPPLY_GEN_EXPONENT));
			rc.broadcast(50030, requiredTowers);
			
			requiredMiners = (int)Math.ceil((factories + units(RobotType.BEAVER)) * 4 / GameConstants.MINER_MINE_MAX); //Assume 2.5 income from miners and average usage from factories and beavers
			rc.broadcast(50031, requiredMiners);
		} catch (GameActionException e) {
			e.printStackTrace();
		}
	}
	
	public void addUnit(RobotType t) {
		int i = t.ordinal();
		unitCounts[i]++; // We just built one of these
		int data = unitCounts[i] << 1;
		if (isIdle[i])
			data |= 1;
		try {
			rc.broadcast(50000+i, data);
		} catch (GameActionException e) {
			System.out.println("Broadcast exception");
			//e.printStackTrace();
		}
	}
	
	private void getBroadcast() {
		unitCounts = new int[22];
		isIdle = new boolean[22];
		
		try {
			for (RobotType t: RobotType.values()) {
				int i = t.ordinal();
				int data = rc.readBroadcast(50000+i);
				isIdle[i] = ((data & 1) != 0)?true:false;
				unitCounts[i] = data >> 1;
			}
			requiredTowers = rc.readBroadcast(50030);
			requiredMiners = rc.readBroadcast(50031);
		} catch (GameActionException e) {
			e.printStackTrace();
		}
	}
	
	public int units(RobotType type) {
		return unitCounts[type.ordinal()];
	}
	
	private boolean idle(RobotType type) {
		return isIdle[type.ordinal()];
	}
	
	// returns the type of unit we should build this turn or null if there is nothing to do
	public RobotType getBuildOrder() {		
		int turn = Clock.getRoundNum();
		getBroadcast();

		switch (rc.getType()) {
		case BEAVER:
			double ore = rc.senseOre(rc.getLocation());
			if (units(RobotType.MINERFACTORY) == 0 && ore > 0 && !rc.hasBuildRequirements(RobotType.MINERFACTORY))
				return null; // Save up until we can afford a mine factory
			/*
			 * We only build a factory if all factories of this type are already busy building, i.e. not idle
			 * This means production is dependent on both need and resources
			 */
			if (rc.hasBuildRequirements(RobotType.MINERFACTORY) && ore > 0 && !idle(RobotType.MINERFACTORY) && turn+RobotType.MINERFACTORY.buildTurns < maxRounds &&
					units(RobotType.MINERFACTORY) < 1+turn/1000)
				return RobotType.MINERFACTORY;
			else if (rc.hasBuildRequirements(RobotType.HELIPAD) && !idle(RobotType.HELIPAD) && turn+RobotType.HELIPAD.buildTurns < maxRounds)
				return RobotType.HELIPAD;
			else if (rc.hasBuildRequirements(RobotType.AEROSPACELAB) && !idle(RobotType.AEROSPACELAB) && turn+RobotType.AEROSPACELAB.buildTurns < maxRounds)
				return RobotType.AEROSPACELAB;
			/*
			else if (rc.hasBuildRequirements(RobotType.BARRACKS) && turn+RobotType.BARRACKS.buildTurns < maxRounds &&
					units(RobotType.COMMANDER) > 0 && units(RobotType.BARRACKS) < 1)
				return RobotType.BARRACKS;
			else if (rc.hasBuildRequirements(RobotType.TANKFACTORY) && !idle(RobotType.TANKFACTORY) && turn+RobotType.TANKFACTORY.buildTurns < maxRounds)
				return RobotType.TANKFACTORY;
			*/
			else if (rc.hasBuildRequirements(RobotType.TECHNOLOGYINSTITUTE) && turn+RobotType.TECHNOLOGYINSTITUTE.buildTurns < maxRounds &&
					units(RobotType.TECHNOLOGYINSTITUTE) == 0 && units(RobotType.AEROSPACELAB) > 0)
				return RobotType.TECHNOLOGYINSTITUTE;
			else if (rc.hasBuildRequirements(RobotType.TRAININGFIELD) && turn+RobotType.TRAININGFIELD.buildTurns < maxRounds &&
					units(RobotType.TRAININGFIELD) == 0)
				return RobotType.TRAININGFIELD;
			else if (turn > 1800 && rc.hasBuildRequirements(RobotType.HANDWASHSTATION) && turn+RobotType.HANDWASHSTATION.buildTurns < maxRounds)
				return RobotType.HANDWASHSTATION;
			else if (rc.hasBuildRequirements(RobotType.SUPPLYDEPOT) && turn+RobotType.SUPPLYDEPOT.buildTurns < maxRounds &&
					units(RobotType.SUPPLYDEPOT) < Math.min(30, requiredTowers))
				return RobotType.SUPPLYDEPOT;
			break;
		case MINERFACTORY:
			if (rc.hasSpawnRequirements(RobotType.MINER) && turn+RobotType.MINER.buildTurns < maxRounds &&
					units(RobotType.MINER) < requiredMiners)
				return RobotType.MINER;
			break;
		case TECHNOLOGYINSTITUTE:
			if (turn > 600 && rc.hasSpawnRequirements(RobotType.COMPUTER) && turn+RobotType.COMPUTER.buildTurns < maxRounds &&
					units(RobotType.COMPUTER) < 2)
				return RobotType.COMPUTER;
			break;
		case BARRACKS:
			break;
		case HELIPAD:
			/*
			if (rc.hasSpawnRequirements(RobotType.DRONE) && turn+RobotType.DRONE.buildTurns < maxRounds &&
					units(RobotType.DRONE)+units(RobotType.LAUNCHER) < 5)
				return RobotType.DRONE;
			*/
			break;
		case TANKFACTORY:
			if (rc.hasSpawnRequirements(RobotType.TANK) && turn+RobotType.TANK.buildTurns < maxRounds)
				return RobotType.TANK;
			break;
		case TRAININGFIELD:
			if (rc.hasSpawnRequirements(RobotType.COMMANDER) && turn+RobotType.COMMANDER.buildTurns < maxRounds &&
					units(RobotType.COMMANDER) == 0)
				return RobotType.COMMANDER;
			break;
		case AEROSPACELAB:
			if (rc.hasSpawnRequirements(RobotType.LAUNCHER) && turn+RobotType.LAUNCHER.buildTurns < maxRounds)
				return RobotType.LAUNCHER;
			break;
		case HQ: //We need more beavers to build factories if we have spare ore
			if (rc.hasSpawnRequirements(RobotType.BEAVER) && turn+RobotType.BEAVER.buildTurns < maxRounds &&
					units(RobotType.BEAVER) < (turn+300)/200)
				return RobotType.BEAVER;
			break;
		default:
			break;
		}
		
		return null;
	}
}
