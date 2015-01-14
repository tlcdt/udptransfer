package tlcnet.udptest;

import java.util.Arrays;

public class DuplicateIdHandler {
	static final int WNDSIZE = 40000;
	static final int WNDSHIFT= 4000;
	public static final int JOLLY = 0;
	
	private int wndShift;
	private int left; // left margin
	private int right;
	private boolean[] memory;
	private int misses = 0;

	
	
	
	
	/**
	 * Create an object to handle duplicate IDs. The window size is the amount of
	 * memory that this object has: higher memory means that it "remembers" older
	 * IDs. Each time a new ID is received that is outside the current window, the
	 * window itself is shifted by wndShift, and the wndShift oldest IDs are
	 * "forgotten". The leftmost (oldest) ID in the window is initialized to the
	 * parameter left.
	 * 
	 * @param wndSize - the window size
	 * @param wndShift - the amount by which the window is shifted automatically when needed
	 * @param left - oldest ID in memory
	 */
	public DuplicateIdHandler(int wndSize, int wndShift, int left) {
		super();
		this.wndShift = wndShift;
		this.left = left;
		right = left + wndSize - 1;
		memory = new boolean[wndSize];
	}


	/**
	 * Create an object to handle duplicate IDs. The window size is the amount of
	 * memory that this object has: higher memory means that it "remembers" older
	 * IDs. Each time a new ID is received that is outside the current window, the
	 * window itself is shifted by wndShift, and the wndShift oldest IDs are
	 * "forgotten". The leftmost (oldest) ID in the window is initialized to 1.
	 * 
	 * @param wndSize - the window size
	 * @param wndShift - the amount by which the window is shifted automatically when needed
	 */
	public DuplicateIdHandler(int wndSize, int wndShift) {
		this(wndSize, wndShift, 1);
	}



	/**
	 * Create an object to handle duplicate IDs. The window size is the amount of
	 * memory that this object has: higher memory means that it "remembers" older
	 * IDs. Each time a new ID is received that is outside the current window, the
	 * window itself is shifted by a default amount, and the oldest IDs are
	 * "forgotten".<br>
	 * The leftmost (oldest) ID in the window is initialized to 1, and
	 * both the window size and the window shift are default values.
	 */
	public DuplicateIdHandler() {
		this(WNDSIZE, WNDSHIFT, 1);		
	}





	/**
	 * Returns true if the specified ID is new, or if it's too old to be remembered,
	 * false otherwise. If the specified ID is new, it will be flagged as processed,
	 * and subsequent calls to this method with the same parameter will return false.
	 * 
	 * @param id - the ID to be inspected
	 * @return - true if the specified ID is new, or if it's too old to be remembered
	 * - false otherwise.
	 */
	public boolean isNew(int id) {
		if(id == JOLLY)
			return true; // without setting it!
		
		if(id < left) {
			misses++;
			return true; // we forgot if it's new or not... assume it's new
		}

		if(id > right) {
			shiftWindow();
			setElement(id);
			return true;
		}

		boolean result = !getElement(id);
		setElement(id);
		return result;
	}

	/**
	 * Flags the specified ID as processed.
	 * 
	 * @param id
	 */
	private void setElement(int id) {
		memory[id - left] = true;
	}

	/**
	 * Returns the state of the specified ID without altering it: the state must be
	 * modified by the caller, i.e. isNew().
	 * 
	 * @param id
	 * @return the state of the specified ID
	 */
	private boolean getElement(int id) {
		return memory[id - left];
	}

	/**
	 * Shifts the memory window.
	 */
	private void shiftWindow() {
		System.arraycopy(memory, wndShift, memory, 0, memory.length - wndShift);
		Arrays.fill(memory, memory.length - wndShift, memory.length, false);
		left += wndShift;
		right += wndShift;
	}

	/**
	 * Returns the number of cache misses.
	 * 
	 * @return the number of cache misses
	 */
	public int misses() {
		return misses;
	}
}
