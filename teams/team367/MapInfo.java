package team367;

import battlecode.common.*;

/*
 * This class stores information about the state of the map
 * This acts as a local cache for the results of a call to rc.senseTerrainTile and includes logic for map symmetry
 */
public class MapInfo {
	private TerrainTile[][] map;
	private RobotController rc;
	MapLocation hq;
	MapLocation ehq;
	//As we discover the edges of the map, these values are filled in.
	int	minY;
	int minX;
	int maxY;
	int maxX;
	
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
		
		// Work out known bounds of map
		minY = Math.min(hq.y, ehq.y);
		maxY = Math.max(hq.y, ehq.y);
		minX = Math.min(hq.x, ehq.x);
		maxX = Math.max(hq.x, ehq.x);
		
		for (MapLocation m: towers) {
			if (m.x < minX)
				minX = m.x;
			if (m.x > maxX)
				maxX = m.x;
			if (m.y < minY)
				minY = m.y;
			if (m.y > maxY)
				maxY = m.y;
		}
		for (MapLocation m: enemy) {
			if (m.x < minX)
				minX = m.x;
			if (m.x > maxX)
				maxX = m.x;
			if (m.y < minY)
				minY = m.y;
			if (m.y > maxY)
				maxY = m.y;
		}
		//System.out.println("Map initialised: Symmetry " + symmetry + " TopLeft " + minX + "," + minY + " BottomRight " + maxX + "," + maxY);
	}
	
	public TerrainTile tile(MapLocation m) {
		int x = cropX(m.x);
		int y = cropY(m.y);
		//Only sense the tile if we don't have a known result
		if (map[x][y] == null || map[x][y] == TerrainTile.UNKNOWN) {
			map[x][y] = rc.senseTerrainTile(m);

			if (map[x][y] == TerrainTile.OFF_MAP) {
				if (m.x < minX)
					minX = m.x;
				if (m.x > maxX)
					maxX = m.x;
				if (m.y < minY)
					minY = m.y;
				if (m.y > maxY)
					maxY = m.y;
			}
			if (symmetry != MapSymmetry.NONE) {
				MapLocation opposite = transform(m, symmetry);
				int ox = cropX(opposite.x);
				int oy = cropY(opposite.y);
			
				if (map[x][y] == TerrainTile.UNKNOWN) {
					if (map[ox][oy] == null || map[ox][oy] == TerrainTile.UNKNOWN)
						map[ox][oy] = rc.senseTerrainTile(opposite);
					map[x][y] = map[ox][oy];
				} else {
					map[ox][oy] = map[x][y];
				}
				
				if (map[ox][oy] == TerrainTile.OFF_MAP) {
					if (opposite.x < minX)
						minX = opposite.x;
					if (opposite.x > maxX)
						maxX = opposite.x;
					if (opposite.y < minY)
						minY = opposite.y;
					if (opposite.y > maxY)
						maxY = opposite.y;
				}
			}
		}
		return map[x][y];
	}
	
	public TerrainTile tile(int x, int y) {
		int mx = x + (hq.x/GameConstants.MAP_MAX_WIDTH)*GameConstants.MAP_MAX_WIDTH;
		int my = y + (hq.y/GameConstants.MAP_MAX_HEIGHT)*GameConstants.MAP_MAX_HEIGHT;

		if (mx > maxX)
			mx -= GameConstants.MAP_MAX_WIDTH;
		else if (mx < minX)
			mx += GameConstants.MAP_MAX_WIDTH;;
		if (my > maxY)
			my -= GameConstants.MAP_MAX_HEIGHT;
		else if (my < minY)
			my += GameConstants.MAP_MAX_HEIGHT;
		//System.out.println("Mapping " + x + "," + y + " to " + mx + "," + my);
		return tile(new MapLocation(mx, my));
	}
	
	public void dump() {
		System.out.println("Map dump: Symmetry " + symmetry + " TopLeft " + minX + "," + minY + " BottomRight " + maxX + "," + maxY);
		for (int y=minY; y <= maxY; y++) {
			int cy = cropY(y);
			for (int x=minX; x <= maxX; x++) {
				int cx = cropX(x);
				if (map[cx][cy] == null || map[cx][cy] == TerrainTile.UNKNOWN)
					System.out.printf("?");
				else if (map[cx][cy] == null || map[cx][cy] == TerrainTile.NORMAL)
					System.out.printf(" ");
				else if (map[cx][cy] == null || map[cx][cy] == TerrainTile.VOID)
					System.out.printf("*");
				else if (map[cx][cy] == null || map[cx][cy] == TerrainTile.OFF_MAP)
					System.out.printf("X");
			}
			System.out.println("");
		}
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
