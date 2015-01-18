package team367;

import battlecode.common.*;

/*
 * We often need to sense enemies in range
 * Sometime within our normal sense range and sometimes at larger ranges to take into account what our allies can see
 * Since each call to the get the enemies is costly we cache the results each turn
 */
public class Sensors {
	private RobotController rc;
	private Team myTeam;
	private Team enemyTeam;
	private int senseRange;
	private int[] enemySensed;
	private RobotInfo[][] enemyData;
	private int[] alliesSensed;
	private RobotInfo[][] allyData;
	private int[] unitsSensed;
	private RobotInfo[][] unitData;
	
	public Sensors(RobotController myrc) {
		rc = myrc;
		senseRange = rc.getType().sensorRadiusSquared;
		enemySensed = new int[5];
		enemyData = new RobotInfo[5][];
		alliesSensed = new int[5];
		allyData = new RobotInfo[5][];
		unitsSensed = new int[5];
		unitData = new RobotInfo[5][];
		
		myTeam = rc.getTeam();
		enemyTeam = myTeam.opponent();
	}
	
	public RobotInfo[] enemies(SensorRange range) {
		int index = range.ordinal();
		
		int now = Clock.getRoundNum();
		if (enemyData[index] == null || enemySensed[index] != now) {
			enemyData[index] = rc.senseNearbyRobots(senseRange*range.multiplier, enemyTeam);
			enemySensed[index] = now;
		}
		
		return enemyData[index];
	}
	
	public RobotInfo[] allies(SensorRange range) {
		int index = range.ordinal();
		
		int now = Clock.getRoundNum();
		if (allyData[index] == null || alliesSensed[index] != now) {
			allyData[index] = rc.senseNearbyRobots(senseRange*range.multiplier, myTeam);
			alliesSensed[index] = now;
		}
		
		return allyData[index];
	}
	
	public RobotInfo[] units(SensorRange range) {
		int index = range.ordinal();
		
		int now = Clock.getRoundNum();
		if (unitData[index] == null || unitsSensed[index] != now) {
			unitData[index] = rc.senseNearbyRobots(senseRange*range.multiplier);
			unitsSensed[index] = now;
		}
		
		return unitData[index];
	}
}
