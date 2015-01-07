package tlcnet.udptest;

import java.util.Arrays;

public class DuplicateIdHandler {
	private static final int WNDSIZE = 40000;
	private static final int WNDSHIFT= 4000;
	public static final int JOLLY = 0;
	
	private int wndShift;
	private int left; // left margin
	private int right;
	private boolean[] memory;
	private int misses = 0;

	
	
	
	
	/**
	 * @param wndSize
	 * @param wndShift
	 * @param left
	 */
	public DuplicateIdHandler(int wndSize, int wndShift, int left) {
		super();
		this.wndShift = wndShift;
		this.left = left;
		right = left + wndSize;
		memory = new boolean[wndSize];
	}

	
	
	
	
	
	/**
	 * @param wndSize
	 * @param wndShift
	 */
	public DuplicateIdHandler(int wndSize, int wndShift) {
		this(wndSize, wndShift, 1);
	}


	

	/**
	 * 
	 */
	public DuplicateIdHandler() {
		this(WNDSIZE, WNDSHIFT, 1);		
	}






	public boolean isNew(int id) {
		if(id == JOLLY)
			return true; // without setting it!
		
		if(id < left) {
			misses++;
			return true; // we forgot... assume it's new
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

	private void setElement(int id) {
		memory[id - left] = true;
	}

	private boolean getElement(int id) {
		return memory[id - left];
	}


	private void shiftWindow() {
		System.arraycopy(memory, wndShift, memory, 0, memory.length - wndShift);
		Arrays.fill(memory, memory.length - wndShift, memory.length, false);
		left += wndShift;
		right += wndShift;
	}


	public int misses() {
		return misses;
	}
}
