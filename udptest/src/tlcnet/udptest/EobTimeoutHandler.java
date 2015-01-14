package tlcnet.udptest;


public class EobTimeoutHandler {
	
	private long[] timers;
	private boolean[] isActive;
	private int[] pendingPkts;
	private final int windowSize;
	private int timeout;
	
	
	
	/**
	 * Initialize this object with the specified window size and timer timeout.
	 * 
	 * @param windowSize - the size of the window
	 * @param timeout - the timeout for each timer
	 */
	public EobTimeoutHandler(int windowSize, int timeout) {
		super();
		if(windowSize < 1)
			throw new IllegalArgumentException();
		this.windowSize = windowSize;
		timers = new long[windowSize];
		isActive = new boolean[windowSize];
		pendingPkts = new int[windowSize];
		setTimeout(timeout);
	}
	
	
	
	/**
	 * Performs a forward shift of the window by one element: this should be called whenever the
	 * main application's sliding windows moves forward by one.
	 */
	public void shiftWindow() {
		Utils.shiftArrayLeft(timers, 1);
		Utils.shiftArrayLeft(isActive, 1);
		Utils.shiftArrayLeft(pendingPkts, 1);
	}
	
	/**
	 * Restarts the timer specified by index, which indicates the position of the
	 * timer in the current window. The corresponding timer, if inactive, is activated.<br>
	 * To start a timer for the first time, this method must be called.
	 * @param index - the index of the timer in the current window
	 */
	public void restartTimer(int index) {
		timers[index] = System.currentTimeMillis();
		isActive[index] = true;
	}
	
	/**
	 * Disables the timer specified by index, which indicates the position of the
	 * timer in the current window.
	 * @param index - the index of the timer in the current window
	 */
	public void disableTimer(int index) {
		isActive[index] = false;
	}
	
	/**
	 * Restarts all timers as specified by restartTimer()
	 */
	public void restartTimers() {
		for(int i = 0; i < windowSize; i++)
			restartTimer(i);
	}
	
	/**
	 * Tells whether the timer specified by index is active at the moment.
	 * @param index - the index of the timer in the current window
	 * @return true if the timer specified by index is active, false otherwise.
	 */
	public boolean isActive(int index) {
		return isActive[index];
	}
	

	
	/**
	 * Returns the number of active timers. Even if timed-out, a timer is still
	 * active if it's not deactivated.
	 * @return the number of active timers.
	 */
	public int getActiveNumber() {
		int n = 0;
		for (int i = 0; i < windowSize; i++)
			if (isActive(i))
				n++;
		return n;
	}

	/**
	 * Returns the elapsed time of the timer specified by index
	 * @param index - the index of the timer in the current window
	 * @return the elapsed time of the timer specified by index. 
	 */
	public int readElapsed(int index) {
		if (isActive(index))
			return (int) (System.currentTimeMillis() - timers[index]);
		return -1;
	}
	
	/**
	 * Sets the timeout for all timers. Its effect is immediate.
	 * @param timeout
	 */
	public void setTimeout(int timeout) {
		if (timeout < 1)
			throw new IllegalArgumentException();
		this.timeout = timeout;
	}
	
	/**
	 * Tells whether the timer specified by index has timed out, i.e. the current time
	 * plus the timeout is greater than the time when the timer was set.<br>
	 * If the timer is inactive, this always returns false
	 * @param index - the index of the timer in the current window
	 * @return true whether this timer is active and has timed out, false otherwise.
	 */
	public boolean hasTimedOut(int index) {
		return readElapsed(index) > timeout;
	}
	
	
	/**
	 * Tells whether some active timer has timed out.
	 * @return true if some active timer has timed out, false otherwise
	 */
	public boolean hasTimedOut() {
		for (int i = 0; i < windowSize; i++)
			if (hasTimedOut(i))
				return true;
		return false;
	}
	
	
	/**
	 * Sets the number of pending packets of the block specified by index.
	 * @param blockIndex - the index of the block in the current window
	 * @param numPkts - the number of pending packets of this block (i.e. packets of this block
	 * that have been sent and are awaiting for an EOB ACK) 
	 */
	public void setPending(int blockIndex, int numPkts) {
		pendingPkts[blockIndex] = numPkts;
	}
	
	/**
	 * Sets to 0 the number of pending packets of the block specified by index.
	 * @param blockIndex - the index of the block in the current window
	 */
	public void resetPending(int blockIndex) {
		pendingPkts[blockIndex] = 0;
	}
	
	/**
	 * Returns the number of pending packets of all blocks (the active ones, i.e. the
	 * ones that have an active timer)
	 * @return the number of pending packets of all blocks
	 */
	public int getPending() {
		int pending = 0;
		for(int i = 0; i < windowSize; i++)
			if (isActive(i))
				pending += pendingPkts[i];
		return pending;
	}
}
