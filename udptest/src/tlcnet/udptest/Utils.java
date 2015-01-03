package tlcnet.udptest;

import java.nio.ByteBuffer;

public class Utils {

	public static int[] byteArr2intArr(byte[] in) {
		int[] out = new int[in.length];
		for (int i = 0; i < in.length; i++) {
			out[i] = in[i] & 0xff; // range 0 to 255
		}
		return out;
	}
	
	public static String byteArr2str(byte[] in) {
		String out = "";
		for (int i = 0; i < in.length; i++) {
			out += " " + (in[i] & 0xff); // range 0 to 255
		}
		return out;
	}
	//This returns a string representing number in binary, with window_size bits
	public static String AckToBinaryString(int number, int window_size)	{
		String b = Integer.toBinaryString((number & 0xffffffff) + 0x80000000).substring(32 - window_size);
		return b;
	}
	
	public static short bytes2short(byte[] bytes) {
	     return ByteBuffer.wrap(bytes).getShort();
	}
}
