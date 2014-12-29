package tlcnet.udptest;

import java.nio.ByteBuffer;

//FIXME These methods are bad: no input check!

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
		for (int i = 0; i < in.length; i++)
			out += " " + (in[i] & 0xff); // range 0 to 255
		return out;
	}
	
	
	public static byte[] int2bytes(int value, int size) {
		if (size==4)
			return ByteBuffer.allocate(4).putInt(value).array();
		if (size==2)
			return ByteBuffer.allocate(2).putShort((short)value).array();
		if (size==3) {
			byte[] out = new byte[3];
			byte[] tmp = ByteBuffer.allocate(4).putInt(value).array();
			System.arraycopy(tmp, 1, out, 0, 3);
			return out;
		}
		return null;
	}
	
	
	public static int bytes2int(byte[] bytes) {
		if (bytes.length == 4)
			return ByteBuffer.wrap(bytes).getInt();
		if (bytes.length < 4) {
			byte[] newbytes = new byte[] {(byte)0, (byte)0, (byte)0, (byte)0};
			System.arraycopy(bytes, 0, newbytes, 4-bytes.length, bytes.length);
			return ByteBuffer.wrap(newbytes).getInt();
		}
		return -1;
	}
	

	
	public static short bytes2short(byte[] bytes) {
	     return ByteBuffer.wrap(bytes).getShort();
	}
	
	
	public static byte[] intarray2bytearray(int[] in, int bytesForEachInt) {
		byte[] out = new byte[in.length * bytesForEachInt];
		
		for (int i = 0; i < in.length; i++) {
			byte[] bytes = int2bytes(in[i], bytesForEachInt);
			System.arraycopy(bytes, 0, out, i * bytesForEachInt, bytesForEachInt);
		}
		
		return out;
	}
	
	
	
	
	public static int count(Object[] array, Object value) {
		int counter = 0;
		for (Object object : array) {
			if (object.equals(value))
				counter++;
		}
		
		return counter;
	}
	
	
	public static int count(boolean[] array, boolean value) {
		int counter = 0;
		for (Object object : array) {
			if (object.equals(value))
				counter++;
		}
		
		return counter;
	}

	
	public static byte[] resizeArray(byte[] array, int newSize) {
		byte[] newArray = new byte[newSize];
		System.arraycopy(array, 0, newArray, 0, Math.min(newSize, array.length));
		return newArray;
	}
	
	
	public static boolean[] resizeArray(boolean[] array, int newSize) {
		boolean[] newArray = new boolean[newSize];
		System.arraycopy(array, 0, newArray, 0, Math.min(newSize, array.length));
		return newArray;
	}
	
	
	public static void logg(Object obj) {
		System.out.println(obj);
	}
}
