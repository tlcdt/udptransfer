package tlcnet.udptest;

public class Channel {
	static final int DEF_CHANNEL_PORT = 9879;

	public static void main(String[] args) {
		new ChannelThread(DEF_CHANNEL_PORT);
	}

}
