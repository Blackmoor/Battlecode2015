package team367;

import battlecode.common.*;

/*
 * This class performs background breadth first searches to a given destination and provides a lookup function
 * for a bot who wants to know the direction to get to the destination.
 * The results are stored in the broadcast space from index 0 (to 45000) and the metadata 500 ints from the end
 */

public class Bfs {

	private static int NUM_PAGES;
	private static int PAGE_SIZE;
	private static int MAP_HEIGHT;
	private static int MAP_WIDTH;
	private static final int MAX_PAGES = 5;
	private static boolean[][] isBlocked = null;

	private static RobotController rc;

	public Bfs(RobotController theRC) {
		rc = theRC;
		MAP_HEIGHT = GameConstants.MAP_MAX_HEIGHT;
		MAP_WIDTH = GameConstants.MAP_MAX_WIDTH;
		PAGE_SIZE = MAP_WIDTH * MAP_HEIGHT;
		NUM_PAGES = Math.min(45000 / PAGE_SIZE, MAX_PAGES);
	}

	private static final int pageMetadataBaseChannel = 45000; //We store the pathfinding status here (uses no more than 5 ints)

	public static final int PRIORITY_HIGH = 2;
	public static final int PRIORITY_LOW = 1;

	// Page allocation:
	// From time to time various different robots will want to use the Bfs class to
	// calculate pathing information for various different destinations. In each case, we need
	// to be able to answer the following questions:
	// - Does a complete, undamaged pathfinding map already exist in some page for the specified destination?
	// If so, no point doing any more work on that destination.
	// - Is there another robot that is at this very moment computing pathing information for the specified destination?
	// If so, no point duplicating their work
	// - If no complete undamaged map exists and no other robot is working on the specified destination, is
	// there a free page that can be used to build a map for the specified destination? By "free" we mean a
	// page that (a) is not at this very moment being added to by another robot and (b) does not contain
	// pathing information for a destination more important than the specified one.
	// If such a free page exists, we can work on it.

	// metadata format: stored in binary
	// fpprrrrxxyy
	// f = finished or not (1 bit)
	// pp = priority (2 bits)
	// rrrr = round last updated (11bits)
	// xx = dest x coordinate (8 bits)
	// yy = dest y coordinate (8 bits)
	private static void writePageMetadata(int page, int roundLastUpdated, MapLocation dest, int priority, boolean finished) throws GameActionException {
		int channel = pageMetadataBaseChannel + page;
		int data = (containsUnknowns ? 1<<30 : 0) | (finished ? 1<<29 : 0) | ((priority & 0x3) << 27) | (roundLastUpdated << 16) | (cropX(dest.x) << 8) | cropY(dest.y);
		rc.broadcast(channel, data);
	}

	private static boolean getMetadataIsFinished(int metadata) {
		return (metadata & (1<<29)) != 0;
	}
	
	private static boolean getMetadataIsComplete(int metadata) {
		return (metadata & (1<<30)) == 0;
	}

	private static int getMetadataPriority(int metadata) {
		return (metadata >> 27) & 0x3;
	}

	private static int getMetadataRoundLastUpdated(int metadata) {
		return (metadata >> 16) & 0x7ff;
	}

	private static MapLocation getMetadataDestination(int metadata) {
		return new MapLocation((metadata>>8)& 0xff, metadata & 0xff);
	}

	private static int readPageMetadata(int page) throws GameActionException {
		int channel = pageMetadataBaseChannel + page;
		int data = rc.readBroadcast(channel);
		return data;
	}

	private static int findFreePage(MapLocation dest, int priority) throws GameActionException {
		// see if we can reuse a page we used before
		if (dest.equals(previousDest) && previousPage != -1) {
			int previousPageMetadata = readPageMetadata(previousPage);
			if (getMetadataRoundLastUpdated(previousPageMetadata) == previousRoundWorked) {
				MapLocation where = getMetadataDestination(previousPageMetadata);
				if (where.x == cropX(dest.x) && where.y == cropY(dest.y) ) {
					if (getMetadataIsFinished(previousPageMetadata)) {
						if (getMetadataIsComplete(previousPageMetadata))
							return -1; // we're done! don't do any work!
						//Restart the search with more up to data map info
						initQueue(dest);
						//System.out.print("Restart BFS to " + dest + ", page " + previousPage+ ", start round " + Clock.getRoundNum() + "\n");
						return previousPage;
					} else {
						return previousPage;
					}
				}
			}
		}

		
		// Check to see if anyone else is working on this destination. If so, don't bother doing anything.
		// But as we loop over pages, look for the page that hasn't been touched in the longest time
		int lastRound = Clock.getRoundNum() - 1;
		int oldestPage = -1;
		int oldestPageRoundUpdated = 999999;
		for (int page = 0; page < NUM_PAGES; page++) {
			int metadata = readPageMetadata(page);
			if (metadata == 0) { // untouched page
				if (oldestPageRoundUpdated > 0) {
					oldestPage = page;
					oldestPageRoundUpdated = 0;
				}
			} else {
				int roundUpdated = getMetadataRoundLastUpdated(metadata);
				boolean isFinished = getMetadataIsFinished(metadata);
				if (roundUpdated >= lastRound || isFinished) {
					if (cropSame(getMetadataDestination(metadata),dest)) {
						return -1; // someone else is on the case!
					}
				}
				if (roundUpdated < oldestPageRoundUpdated) {
					oldestPageRoundUpdated = roundUpdated;
					oldestPage = page;
				}
			}
		}

		// No one else is working on our dest. If we found an inactive page, use that one.
		if (oldestPage != -1 && oldestPageRoundUpdated < lastRound) return oldestPage;

		// If there aren't any inactive pages, and we have high priority, just trash page 0:
		if (priority == PRIORITY_HIGH) return 0;

		// otherwise, give up:
		return -1;
	}

	// Set up the queues
	//We need 2 queues as some moves are diagonal and take more than 1 turn
	private static final int NUM_QUEUES = 2;
	private static final int MAX_QUEUE_SIZE = 400;
	private static final double moveDelay = 1.0;
	private static final double diagonalDelay = 1.4;

	private static int[] locQueues = null;
	private static int currentQ = 0;
	private static boolean containsUnknowns = false;
	private static int[] qHeads = new int[NUM_QUEUES]; //The index into the locQueue for the head of this queue
	private static int[] qTails = new int[NUM_QUEUES]; //The index into the locQueue for the tail of this queue
	private static boolean[][] processed = null; //Set to true when the location is put into 1 of the BFS queues
//	private static Direction[][] path = null; //Filled in when we determine a direction - the master copy is broadcast, this is just for debug

	private static Direction[] dirs = new Direction[] { Direction.NORTH_WEST, Direction.SOUTH_WEST, Direction.SOUTH_EAST, Direction.NORTH_EAST,
			Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.EAST };
	private static int[] dirsX = new int[] { 1, 1, -1, -1, 0, 1, 0, -1 };
	private static int[] dirsY = new int[] { 1, -1, -1, 1, 1, 0, -1, 0 };

	private static MapLocation previousDest = null;
	private static int previousRoundWorked = -1;
	private static int previousPage = -1;

	// initialize the BFS algorithm
	private static void initQueue(MapLocation dest) {
		locQueues = new int[MAX_QUEUE_SIZE*NUM_QUEUES]; //data is xxxxxxxxyyyyyyyyaaaaaaaaaaaaaaaa (x, y coord, a = 10*number of action delays (dist))
		currentQ = 0;
		containsUnknowns = false; // If we find tiles that are unknown we remember so we can restart the process later on
		for (int i=0; i<NUM_QUEUES; i++) {
			qHeads[i] = i*MAX_QUEUE_SIZE;
			qTails[i] = i*MAX_QUEUE_SIZE;
		}
		
		isBlocked = new boolean[MAP_WIDTH][MAP_HEIGHT];
		
		/*
		 * Map Coordinates are offset by a random number for each game and can be negative
		 * By sensing the HQs we can work out the tile in the middle of the board
		 */
		MapLocation hq = rc.senseHQLocation();
		MapLocation ehq = rc.senseEnemyHQLocation();
		int midX = (hq.x + ehq.x) / 2;
		int midY = (hq.y + ehq.y) / 2;
		int maxX = midX + MAP_WIDTH / 2;
		int maxY = midY + MAP_HEIGHT / 2;
		int minX = maxX - MAP_WIDTH;
		int minY = maxY - MAP_HEIGHT;
		
		/*
		 * We don't have full map data - some tiles are unknown but we can use symmetry to fill in some blanks
		 * The maps are either a reflection or a rotation - in most cases we can tell from the hq and tower locations
		 * 
		 * Reflections in the x access will have hq's with the same y coordinate
		 * Reflections in the y access will have hq's with the same x coordinate
		 * Assume a rotation if no reflection is found
		 */
		int symmentry = 0; // rotation
		if (hq.x == ehq.x)
			symmentry = 1; // reflection on x axis
		else if (hq.y == ehq.y)
			symmentry = 2; // reflection on y axis
		
		for (int y=minY; y < maxY; y++) {
			for (int x=minX; x < maxX; x++) {
				TerrainTile tt = rc.senseTerrainTile(new MapLocation(x, y));
				if (tt == TerrainTile.UNKNOWN) {
					switch (symmentry) {
					case 0: //rotation
						tt = rc.senseTerrainTile(new MapLocation(hq.x + ehq.x - x, hq.y + ehq.y - y));
						break; 
					case 1: // reflection on x axis
						tt = rc.senseTerrainTile(new MapLocation(x, hq.y + ehq.y - y));
						break;
					case 2: // reflection on y axis
						tt = rc.senseTerrainTile(new MapLocation(hq.x + ehq.x - x, y));
						break;
					}
				}
				isBlocked[cropX(x)][cropY(y)] = !tt.isTraversable();
				if (tt == TerrainTile.UNKNOWN)
					containsUnknowns = true;
				
				/*
				switch(tt) {
				case VOID: System.out.print("."); break;
				case NORMAL: System.out.print(" "); break;
				case UNKNOWN: System.out.print("?"); break;
				case OFF_MAP: System.out.print("X"); break;
				}
				*/
									
			}
			//System.out.println("");
		}
		
		//HQs and towers (TODO) block movement
		isBlocked[cropX(hq.x)][cropY(hq.y)] = true;
		isBlocked[cropX(ehq.x)][cropY(ehq.y)] = true;
		
		processed = new boolean[MAP_WIDTH][MAP_HEIGHT];	
		//Copy the isBlocked array as walls don't need to be processed
		for(int i = 0; i < MAP_WIDTH; i++)
			 System.arraycopy(isBlocked[i], 0, processed[i], 0, MAP_HEIGHT);
		
//		path = new Direction[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];
//		for (int y=0; y<GameConstants.MAP_MAX_HEIGHT; y++)
//			for (int x=0; x<GameConstants.MAP_MAX_WIDTH; x++)
//				path[x][y] = Direction.NONE;

		// Push dest onto queue
		locQueues[0] = (cropX(dest.x) << 24) | (cropY(dest.y) << 16);
		qTails[0]++;
		processed[cropX(dest.x)][cropY(dest.y)] = true;
	}
	

	
	// broadcast any changed BFS data

	// HQ or TOWERs calls this function to spend spare bytecodes computing paths for soldiers
	public void work(MapLocation dest, int priority, int stopWhen) throws GameActionException {

		int page = findFreePage(dest, priority);
		if (page == -1) {
			return; // We can't do any work, or don't have to
		}
		doWork(dest, priority, stopWhen, page);
	}
	
	private static void doWork(MapLocation dest, int priority, int stopWhen, int page) throws GameActionException {
		if (!dest.equals(previousDest)) {
			initQueue(dest);
			//System.out.print("Cleanser BFS to " + dest + ", page " + page+ ", start round " + Clock.getRoundNum() + "\n");
		}

		previousDest = dest;
		previousPage = page;
		previousRoundWorked = Clock.getRoundNum();
				
		int emptyCount = 0;
		while (emptyCount < NUM_QUEUES && qHeads[currentQ] == qTails[currentQ]) {//Skip over empty queues
			emptyCount++;
			currentQ = (currentQ + 1) % NUM_QUEUES;
		}
		if (emptyCount == NUM_QUEUES)
			return; //Finished
		
		while (Clock.getBytecodesLeft() > stopWhen) {			
			// pop a location from the queue
			int data = locQueues[qHeads[currentQ]];
			int locX = data >> 24;
			int locY = (data >> 16) & 0xff;
			double delay = (data & 0xffff) / 10;
			if (++qHeads[currentQ] % MAX_QUEUE_SIZE == 0)
				qHeads[currentQ] -= MAX_QUEUE_SIZE;
			
			for (int i = 8; i-- > 0;) {
				int x = cropX(locX + dirsX[i]);
				int y = cropY(locY + dirsY[i]);
				boolean isDiagonal = (i<=3);				
				
				if (!processed[x][y]) {
					processed[x][y] = true;
					MapLocation newLoc = new MapLocation(x, y);
					// push newLoc onto queue - pick queue according to how long the move takes
					double newDelay;
					if (isDiagonal)
						newDelay = diagonalDelay;
					else
						newDelay = moveDelay;
					int newQ = (currentQ + (int)(newDelay + (delay % 1))) % NUM_QUEUES;
					newDelay += delay;
					locQueues[qTails[newQ]] = (x << 24) | (y << 16) | (int)(newDelay*10);
					if (++qTails[newQ] % MAX_QUEUE_SIZE == 0)
						qTails[newQ] -= MAX_QUEUE_SIZE;
					//System.out.print("Adding " + x + ", " + y + " to queue " + newQ + " element " + qTails[newQ] + "\n");
					publishResult(page, newLoc, dest, dirs[i], (int)newDelay);																				
				}
			}
			emptyCount = 0;
			while (emptyCount < NUM_QUEUES && qHeads[currentQ] == qTails[currentQ]) {//Skip over empty queues
				emptyCount++;
				currentQ = (currentQ + 1) % NUM_QUEUES;
			}
			if (emptyCount == NUM_QUEUES) {	
				/*
				 * DEBUG to show route
				System.out.print("Cleanser BFS to " + dest + ", page " + page+ " completed on round " + Clock.getRoundNum() +"\n");
				MapLocation m = rc.senseHQLocation().add(Direction.NORTH);
				Direction d = readResult(m, rc.senseEnemyHQLocation());				
				while (d != null) {
					System.out.println(d);
					m = m.add(d);
					d = readResult(m, rc.senseEnemyHQLocation());
				}
				*/
				break;
			}
		}
		
		writePageMetadata(page, previousRoundWorked, dest, priority, (emptyCount == NUM_QUEUES));
	}

	private static int locChannel(int page, MapLocation loc) {
		return PAGE_SIZE * page + MAP_HEIGHT * cropX(loc.x) + cropY(loc.y);
	}

	// We store the data in this format:
	// 1000ddddaaaaaaaaxxxxxxxxyyyyyyyy
	// 1 = validation to prevent mistaking the initial 0 value for a valid pathing instruction
	// d = direction to move (enum ordinal)
	// a = actions (turns) to move here
	// x = x coordinate of destination
	// y = y coordinate of destination
	private static void publishResult(int page, MapLocation here, MapLocation dest, Direction dir, int actions) {
		int data = 0x80;
		data |= dir.ordinal();
		data <<= 8;
		data |= actions;
		data <<= 8;
		data |= cropX(dest.x);
		data <<= 8;
		data |= cropY(dest.y);
		int channel = locChannel(page, here);
		try {
			rc.broadcast(channel, data);
		} catch (GameActionException e) {
			e.printStackTrace();
		}
	}

	// Soldiers call this to get pathing directions
	public Direction readResult(MapLocation here, MapLocation dest) {
		for (int page = 0; page < NUM_PAGES; page++) {
			int data;
			try {
				data = rc.readBroadcast(locChannel(page, here));
			} catch (GameActionException e) {
				e.printStackTrace();
				return null;
			}			
			if (data != 0) { // all valid published results are != 0
				int y = data & 0xff;
				data >>= 8;
				int x = data & 0xff;
				data >>= 8;
				int actions = data & 0xff;
				data >>= 8;
				int dir = data & 0xf;
				
				if (cropX(dest.x) == x && cropY(dest.y) == y) {
					return Direction.values()[dir];
				}
			}
		}
		return null;
	}
	
	// Coords are offset by a large amount and can be negative
	private static int cropX(int w) {
		return crop(w, MAP_WIDTH);
	}
	
	private static int cropY(int h) {
		return crop(h, MAP_HEIGHT);
	}
	private static int crop(int c, int m) {
		return ((c % m) + m) % m;
	}
	
	private static boolean cropSame(MapLocation a, MapLocation b) {
		return (cropX(a.x) == cropX(b.x) && cropY(a.y)== cropY(b.y));
	}
}
