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
	
	
	public static byte[] int2bytes(int value, int size) {
		if (size==4)
			return ByteBuffer.allocate(4).putInt(value).array();
		if (size==2)
			return ByteBuffer.allocate(2).putShort((short)value).array();
		return null;
	}
}
