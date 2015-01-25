package team367;

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
	private int oreSpent; // The total ore spent this turn
	private int maxRounds; // The turn on which the game will end
	
	public BuildStrategy(RobotController myrc) {
		rc = myrc;
		requiredTowers = 0;
		requiredMiners = 0;
		maxRounds = rc.getRoundLimit();
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
			
			if (Clock.getRoundNum() * 10 > rc.getRoundLimit() * 7) //70% of the way through the game we stop producing miners
				requiredMiners = 0;
			else
				requiredMiners = (int)Math.ceil(factories * 8 / GameConstants.MINER_MINE_MAX); //Assume 2.5 income from miners and average usage from factories and beavers
			rc.broadcast(50031, requiredMiners);
			oreSpent = rc.readBroadcast(50032);
			rc.broadcast(50032, 0); // Zero the amount of ore spent this round - other bots add to this value
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
			oreSpent += t.oreCost;
			rc.broadcast(50032, oreSpent);
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
			oreSpent = rc.readBroadcast(50032);
		} catch (GameActionException e) {
			System.out.println("Broadcast exception");
			//e.printStackTrace();
		}
	}
	
	public int units(RobotType type) {
		return unitCounts[type.ordinal()];
	}
	
	private boolean idle(RobotType type) {
		return isIdle[type.ordinal()];
	}
	
	public int oreSpent() {
		return this.oreSpent;
	}
	
	// returns the type of unit we should build this turn or null if there is nothing to do
	public RobotType getBuildOrder() {		
		int turn = Clock.getRoundNum();
		getBroadcast();

		switch (rc.getType()) {
		case BEAVER:
			if (units(RobotType.MINERFACTORY) == 0 && !rc.hasBuildRequirements(RobotType.MINERFACTORY))
				return null; // Save up until we can afford a mine factory
			/*
			 * We only build a factory if all factories of this type are already busy building, i.e. not idle
			 * This means production is dependent on both need and resources
			 */
			if (rc.hasBuildRequirements(RobotType.MINERFACTORY) && !idle(RobotType.MINERFACTORY) && turn+RobotType.MINERFACTORY.buildTurns < maxRounds &&
					units(RobotType.MINERFACTORY) < 1)
				return RobotType.MINERFACTORY;
			else if (rc.hasBuildRequirements(RobotType.HELIPAD) && !idle(RobotType.HELIPAD) && turn+RobotType.HELIPAD.buildTurns < maxRounds &&
					units(RobotType.HELIPAD) == 0)
				return RobotType.HELIPAD;
			else if (rc.hasBuildRequirements(RobotType.AEROSPACELAB) && !idle(RobotType.AEROSPACELAB) && turn+RobotType.AEROSPACELAB.buildTurns < maxRounds)
				return RobotType.AEROSPACELAB;
			/*
			else if (rc.hasBuildRequirements(RobotType.BARRACKS) && !idle(RobotType.BARRACKS) && turn+RobotType.BARRACKS.buildTurns < maxRounds)
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
			else if (maxRounds - turn < 200 && rc.hasBuildRequirements(RobotType.HANDWASHSTATION))// && turn+RobotType.HANDWASHSTATION.buildTurns < maxRounds)
				return RobotType.HANDWASHSTATION;
			else if (rc.hasBuildRequirements(RobotType.SUPPLYDEPOT) && turn+RobotType.SUPPLYDEPOT.buildTurns < maxRounds &&
					units(RobotType.SUPPLYDEPOT) < Math.min(36, requiredTowers))
				return RobotType.SUPPLYDEPOT;
			break;
		case MINERFACTORY:
			if (rc.hasSpawnRequirements(RobotType.MINER) && turn+RobotType.MINER.buildTurns < maxRounds &&
					units(RobotType.MINER) < requiredMiners) //Math.min(units(RobotType.LAUNCHER)+20, requiredMiners))
				return RobotType.MINER;
			break;
		case TECHNOLOGYINSTITUTE:
			if (rc.hasSpawnRequirements(RobotType.COMPUTER) && turn+RobotType.COMPUTER.buildTurns < maxRounds &&
					turn > 600 && units(RobotType.COMPUTER) < 1)
				return RobotType.COMPUTER;
			break;
		case BARRACKS:
			if (rc.hasSpawnRequirements(RobotType.SOLDIER) && turn+RobotType.SOLDIER.buildTurns < maxRounds &&
					units(RobotType.SOLDIER) + units(RobotType.TANK) < 20)
				return RobotType.SOLDIER;
			break;
		case HELIPAD:
			if (rc.hasSpawnRequirements(RobotType.DRONE) && turn+RobotType.DRONE.buildTurns < maxRounds &&
					units(RobotType.DRONE) < 1+turn/850)
				return RobotType.DRONE;
			break;
		case TANKFACTORY:
			if (rc.hasSpawnRequirements(RobotType.TANK) && turn+RobotType.TANK.buildTurns < maxRounds &&
					units(RobotType.TANK) + units(RobotType.LAUNCHER) < 30)
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
