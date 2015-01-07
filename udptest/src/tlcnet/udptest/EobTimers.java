package tlcnet.udptest;


public class EobTimers {
	
	private long[] timers;
	private final int windowSize;
	private int windowLeft;
	
	
	
	/**
	 * This constructor provides this object of all its final fields.
	 */
	public EobTimers(int cacheSize) {
		super();
		this.windowSize = cacheSize;
		timers = new long[cacheSize];
		windowLeft = 1;
	}
	
	
	
	/**
	 * Performs a forward shift of the window by one element: this should be called whenever the
	 * main application's sliding windows moves forward by one.
	 */
	public void shiftWindow() {
		windowLeft++;
		Utils.shiftArrayLeft(timers, 1);
	}
	
	
	public void reset(int index) {
		timers[index] = System.currentTimeMillis();
	}
	
	public void reset() {
		for(int i = 0; i < windowSize; i++)
			reset(i);
	}
	


}
