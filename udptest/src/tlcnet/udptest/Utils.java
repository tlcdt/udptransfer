package tlcnet.udptest;

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
}
