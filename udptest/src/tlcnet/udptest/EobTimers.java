package tlcnet.udptest;


public class EobTimers {
	
	private long[] timers;
	private boolean[] isActive;
	private final int windowSize;
	private int timeout;
	
	
	
	/**
	 * This constructor provides this object of all its final fields.
	 */
	public EobTimers(int windowSize, int timeout) {
		super();
		if(windowSize < 1)
			throw new IllegalArgumentException();
		this.windowSize = windowSize;
		timers = new long[windowSize];
		isActive = new boolean[windowSize];
		setTimeout(timeout);
	}
	
	
	
	/**
	 * Performs a forward shift of the window by one element: this should be called whenever the
	 * main application's sliding windows moves forward by one.
	 */
	public void shiftWindow() {
		Utils.shiftArrayLeft(timers, 1);
		Utils.shiftArrayLeft(isActive, 1);
	}
	
	
	public void restartTimer(int index) {
		timers[index] = System.currentTimeMillis();
		isActive[index] = true;
	}
	
	public void disableTimer(int index) {
		isActive[index] = false;
	}
	
	public void restartTimers() {
		for(int i = 0; i < windowSize; i++)
			restartTimer(i);
	}
	
	public boolean isActive(int index) {
		return isActive[index];
	}
	
	public int getActiveNumber() {
		int n = 0;
		for (int i = 0; i < windowSize; i++)
			if (isActive(i))
				n++;
		return n;
	}

	public int readElapsed(int index) {
		if (isActive(index))
			return (int) (System.currentTimeMillis() - timers[index]);
		return -1;
	}
	
	public void setTimeout(int timeout) {
		if (timeout < 1)
			throw new IllegalArgumentException();
		this.timeout = timeout;
	}
	
	public boolean hasTimedOut(int index) {
		return readElapsed(index) > timeout;
	}
	
	public boolean hasTimedOut() {
		for (int i = 0; i < windowSize; i++)
			if (hasTimedOut(i))
				return true;
		return false;
	}
}
