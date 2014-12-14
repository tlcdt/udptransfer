package tlcnet.udptest;

public class Server {
	static final int DEF_SERVER_PORT = 9878;

	public static void main(String[] args) {
		new ServerThread(DEF_SERVER_PORT);
	}

}
