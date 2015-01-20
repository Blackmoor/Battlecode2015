package teamv36;

import java.util.Arrays;

import battlecode.common.*;

/*
 * This class stores information about the state of the map
 * This acts as a local cache for the results of a call to rc.senseTerrainTile and includes logic for map symmetry
 */
public class MapInfo {
	private TerrainTile[][] map;
	private int[][] lastSensed;
	private RobotController rc;
	MapLocation hq;
	MapLocation ehq;
	
	private enum MapSymmetry {
		NONE,
		ROTATION, //180 degrees
		REFLECT_X, // A reflection along a line parallel to the x axis
		REFLECT_Y, // A reflection along a line parallel to the y axis
		REFLECT_SLASH, // A Diagonal reflection that looks like a /
		REFLECT_BACKSLASH // A diagonal reflection that looks like a \
	}
	private MapSymmetry symmetry;
	
	public MapInfo(RobotController myrc) {
		rc = myrc;
		map = new TerrainTile[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];
		lastSensed = new int[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];
		for (int x=0; x<GameConstants.MAP_MAX_WIDTH; x++)
			Arrays.fill(lastSensed[x], -1);
		hq = rc.senseHQLocation();
		ehq = rc.senseEnemyHQLocation();
		MapLocation[] towers = rc.senseTowerLocations();
		MapLocation[] enemy = rc.senseEnemyTowerLocations();
		
		if (transform(hq, MapSymmetry.ROTATION).equals(ehq) && (towers.length == 0 ||
				(towers.length > 0 && transform(towers[0], MapSymmetry.ROTATION).equals(enemy[0]))))
			symmetry = MapSymmetry.ROTATION;
		else if (transform(hq, MapSymmetry.REFLECT_X).equals(ehq))
			symmetry = MapSymmetry.REFLECT_X;
		else if (transform(hq, MapSymmetry.REFLECT_Y).equals(ehq))
			symmetry = MapSymmetry.REFLECT_Y;
		else if (transform(hq, MapSymmetry.REFLECT_SLASH).equals(ehq))
			symmetry = MapSymmetry.REFLECT_SLASH;
		else if (transform(hq, MapSymmetry.REFLECT_BACKSLASH).equals(ehq))
			symmetry = MapSymmetry.REFLECT_BACKSLASH;
		else
			symmetry = MapSymmetry.NONE;
	}
	
	public TerrainTile tile(MapLocation m) {
		int now = Clock.getRoundNum();
		int x = cropX(m.x);
		int y = cropY(m.y);
		if (lastSensed[x][y] == -1 || (lastSensed[x][y] < now && map[x][y] == TerrainTile.UNKNOWN)) {
			map[x][y] = rc.senseTerrainTile(m);
			lastSensed[x][y] = now;
			
			if (map[x][y] != TerrainTile.UNKNOWN && symmetry != MapSymmetry.NONE) { //Fill in the symmetry tile too
				MapLocation opposite = transform(m, symmetry);
				map[cropX(opposite.x)][cropY(opposite.y)] = map[x][y];
				lastSensed[cropX(opposite.x)][cropY(opposite.y)] = now;
			}
		}
		return map[x][y];
	}
	
	private MapLocation transform(MapLocation m, MapSymmetry s) {
		int x;
		int y;
		
		switch (s) {
		case ROTATION:
			x = hq.x + ehq.x - m.x;
			y = hq.y + ehq.y - m.y;
			break;
		case REFLECT_X:
			x = m.x;
			y = hq.y + ehq.y - m.y;
			break;
		case REFLECT_Y:
			x = hq.x + ehq.x - m.x;
			y = m.y;
			break;
		case REFLECT_SLASH:
			x = m.y + (hq.x + ehq.x - hq.y - ehq.y)/2;
			y = m.x + (hq.y + ehq.y - hq.x - ehq.x)/2;
			break;
		case REFLECT_BACKSLASH:
			x = (hq.x + ehq.x + hq.y + ehq.y)/2 - m.y;
			y = (hq.x + ehq.x + hq.y + ehq.y)/2 - m.x;
			break;
		default:
			return null;
		}
		
		return new MapLocation(x, y);
	}
	
	// Coords are offset by a large amount and can be negative
	private static int cropX(int w) {
		return crop(w, GameConstants.MAP_MAX_WIDTH);
	}
	
	private static int cropY(int h) {
		return crop(h, GameConstants.MAP_MAX_HEIGHT);
	}
	private static int crop(int c, int m) {
		return ((c % m) + m) % m;
	}
}
