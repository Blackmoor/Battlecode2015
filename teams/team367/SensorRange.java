package team367;

public enum SensorRange {
	VISIBLE (1),
	CLOSE (2),
	LONG (4),
	EXTREME (16),
	ALL (100);
	
	int multiplier;
	
	SensorRange(int m) {
		multiplier = m;
	}
}
